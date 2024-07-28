package edu.duke.bartesaghi.micromon.cluster.slurm

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.Commands
import edu.duke.bartesaghi.micromon.linux.Command
import edu.duke.bartesaghi.micromon.linux.userprocessor.deleteAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.readStringAs
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.ClusterJobResultType
import edu.duke.bartesaghi.micromon.services.ClusterMode
import edu.duke.bartesaghi.micromon.services.ClusterQueues
import java.nio.file.Path
import kotlin.collections.ArrayList


class SBatch(val config: Config.Slurm) : Cluster {

	override val clusterMode = ClusterMode.SLURM

	override val commandsConfig: Commands.Config get() =
		config.commandsConfig

	override val queues: ClusterQueues =
		ClusterQueues(
			config.queues,
			config.gpuQueues
		)

	private val sshPool = SSHPool(config.sshPoolConfig)

	override fun validate(job: ClusterJob) {

		val args = job.argsPosix

		// look for any banned arguments
		if (args.any { it.startsWith("--output=") }) {
			throw IllegalArgumentException("job outputs are handled automatically by nextPYP, no need to specify them explicitly")
		}
		if (args.any { it.startsWith("--error=") }) {
			throw IllegalArgumentException("job errors are handled automatically by nextPYP, no need to specify them explicitly")
		}
		if (args.any { it.startsWith("--chdir=") }) {
			throw IllegalArgumentException("the submission directory is handled automatically by nextPYP, no need to specifiy it explicitly")
		}
		if (args.any { it.startsWith("--array=") }) {
			throw IllegalArgumentException("the array is handled automatically by nextPYP, no need to specify it explicitly")
		}

		// validate the queue names, if any are given
		val queues = config.queues + config.gpuQueues
		args.filter { it.startsWith("--partition=") }
			.map { it.substring("--partition=".length) }
			.forEach { queue ->
				if (queue !in queues) {
					throw IllegalArgumentException("the partition '$queue' was not valid, try one of $queues")
				}
			}

		// these are errors caused by users (rather than programmers),
		// so remap the error types so the errors can be shown in the UI
		try {

			// validate any gres arguments
			args.filter { it.startsWith("--gres=") }
				.forEach { Gres.parseAll(it) }

		} catch (t: Throwable) {
			throw ClusterJob.ValidationFailedException(t)
		}
	}

	override fun validateDependency(depId: String) {
		// sbatch will validate the dependency ids, so we don't have to do it here
	}

	override suspend fun launch(clusterJob: ClusterJob, depIds: List<String>, scriptPath: Path): ClusterJob.LaunchResult? {

		// build the sbatch command and add any args
		// NOTE: argument sanitization is handled by Command.toSafeShellString(), so we don't need to double-sanitize each argument here
		var sbatch = Command(config.cmdSbatch)

		sbatch.args.add("--output=${clusterJob.outPathMask()}")

		// add all the arguments from the job submission
		sbatch.args.addAll(clusterJob.args)

		if (depIds.isNotEmpty()) {
			sbatch.args.add("--dependency=afterany:${depIds.joinToString(",")}")
		}

		// use the default cpu queue, if none was specified in the args
		val partition = clusterJob.argsPosix
			.find { it.startsWith("--partition=") }
			?.split("=")
			?.lastOrNull()
		if (partition == null) {
			// that is, if any default queue exists
			config.queues.firstOrNull()
				?.let { sbatch.args.add("--partition=$it") }
		}

		// render the array arguments
		clusterJob.commands.arraySize?.let { arraySize ->
			val bundleInfo = clusterJob.commands.bundleSize
				?.let { "%$it" }
				?: ""
			sbatch.args.add("--array=1-$arraySize$bundleInfo")
		}

		// set the job name, if any
		clusterJob.clusterName?.let {
			sbatch.args.add("--job-name=$it")
		}

		sbatch.args.add("$scriptPath")

		// run the job as the specified OS user, if needed
		if (clusterJob.osUsername != null) {
			sbatch = Backend.userProcessors.get(clusterJob.osUsername).wrap(sbatch)
		}

		// add environment vars from the job submission
		sbatch.envvars.addAll(clusterJob.env)

		// render the command into something suitable for a remote shell,
		// ie, with proper argument quoting to avoid injection attacks
		val sbatchCmd = sbatch.toShellSafeString()

		// call sbatch on the SLURM host and wait for the job to submit
		val result = sshPool
			.connection {

				// last chance to abort submission before SLURM gets the job
				if (clusterJob.getLog()?.wasCanceled() == true) {
					// don't submit the command
					null
				} else {
					// submit the command to the login node
					exec(sbatchCmd)
				}
			}
			?: return null

		// get the job id from sbatch stdout
		val sbatchId = result.console
			.lastOrNull()
			// NOTE: the user-processor can add messages before the sbatch output,
			//       so just look at the last line of the console output
			?.takeIf { it.startsWith("Submitted batch job ") }
			?.split(" ")
			?.last()
			?.toLongOrNull()
			?: throw ClusterJob.LaunchFailedException("unable to read job id from sbatch output", result.console, sbatchCmd)

		return ClusterJob.LaunchResult(
			sbatchId,
			result.console.joinToString("\n"),
			sbatchCmd
		)
	}

	override suspend fun waitingReason(clusterJob: ClusterJob, launchResult: ClusterJob.LaunchResult): String? =
		launchResult.jobId
			?.let { SQueue(sshPool, config, clusterJob.osUsername).reason(it) }
			?.let { "Job has been submitted to SLURM. The SLURM status is: ${it.name}: ${it.description}" }

	override suspend fun cancel(clusterJobs: List<ClusterJob>) {

		// short circuit, just in case
		if (clusterJobs.isEmpty()) {
			return
		}

		// build the cancel commands
		val commands = clusterJobs
			.mapNotNull f@{ clusterJob ->

				// get the SLURM job id for this job, if any
				val slurmId = clusterJob.getLogOrThrow().launchResult?.jobId
					?: return@f null

				// build the scancel command
				var scancel = Command(config.cmdScancel, slurmId.toString())

				// run the job as the specified OS user, if needed
				if (clusterJob.osUsername != null) {
					scancel = Backend.userProcessors.get(clusterJob.osUsername).wrap(scancel)
				}

				scancel
			}

		// run the commands on the SLURM login node
		sshPool.connection {
			for (cmd in commands) {
				exec(cmd.toShellSafeString())
			}
		}
	}

	override suspend fun jobResult(clusterJob: ClusterJob, arrayIndex: Int?): ClusterJob.Result {

		// read the job output and cleanup the log file
		val outPath = clusterJob.outPath(arrayIndex)
		val out: String? = try {
			val out = outPath.readStringAs(clusterJob.osUsername)
			outPath.deleteAs(clusterJob.osUsername)
			out
		} catch (t: Throwable) {
			Backend.log.warn("Failed to retrieve cluster job output log file from: $outPath", t)
			// maybe it doesn't exist? probably the process just didn't write to stdout or stderr?
			// or maybe there's some kind of network delay and we just can't see the log file yet?
			// TODO: wait a bit and try to download the log file again?
			null
		}

		// determine the job result
		// NOTE: when SLURM terminates a job, a line like this gets written to the output:
		//slurmstepd-mathpad: error: *** JOB 13 ON mathpad CANCELLED AT 2020-07-16T11:37:37 DUE TO TIME LIMIT ***
		//slurmstepd-mathpad: error: *** JOB 17 ON mathpad CANCELLED AT 2020-07-16T16:56:04 ***
		//slurmstepd: error: *** JOB 937 ON localhost CANCELLED AT 2022-11-17T14:56:35 ***
		val cancelPattern = Regex("slurmstepd(-\\w+)?: error: \\*\\*\\* JOB \\d+ ON \\w+ CANCELLED AT [0-9-:T]+ (.*) ?\\*\\*\\*")
		val cancelMatch = out
			?.lines()
			?.firstNotNullOfOrNull { cancelPattern.matchEntire(it) }
			// there wouldn't be more than one cancel line in the output, right?

		return if (cancelMatch != null) {
			val reason = cancelMatch
				.groupValues
				.getOrNull(2)
			ClusterJob.Result(ClusterJobResultType.Canceled, out, reason)
		} else {
			ClusterJob.Result(ClusterJobResultType.Success, out)
		}
	}
}


fun ArgValues.toSbatchArgs(): List<String> =
	ArrayList<String>().apply {
		add("--cpus-per-task=$slurmLaunchCpus")
		add("--mem=${slurmLaunchMemory}G")
		add("--time=$slurmLaunchWalltime")
		slurmLaunchQueue?.let { add("--partition=$it") }
		slurmLaunchAccount?.let { add("--account=$it") }
		slurmLaunchGres?.let { add("--gres=$it") }
	}
	// NOTE: argument sanitization is handled by the job submitter, so we don't need to double-sanitize each argument here
