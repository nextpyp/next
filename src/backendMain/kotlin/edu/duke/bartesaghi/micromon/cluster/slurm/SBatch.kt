package edu.duke.bartesaghi.micromon.cluster.slurm

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.Commands
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

		fun List<String>.validateArgs() {

			// look for any banned arguments
			if (any { it.startsWith("--output=") }) {
				throw IllegalArgumentException("job outputs are handled automatically by nextPYP, no need to specify them explicitly")
			}
			if (any { it.startsWith("--error=") }) {
				throw IllegalArgumentException("job errors are handled automatically by nextPYP, no need to specify them explicitly")
			}
			if (any { it.startsWith("--chdir=") }) {
				throw IllegalArgumentException("the submission directory is handled automatically by nextPYP, no need to specifiy it explicitly")
			}
			if (any { it.startsWith("--array=") }) {
				throw IllegalArgumentException("the array is handled automatically by nextPYP, no need to specify it explicitly")
			}

			// validate the queue names, if any are given
			val queues = config.queues + config.gpuQueues
			this.filter { it.startsWith("--partition=") }
				.map { it.substring("--partition=".length).unquote() }
				.forEach { queue ->
					if (queue !in queues) {
						throw IllegalArgumentException("the partition '$queue' was not valid, try one of $queues")
					}
				}
		}

		job.args.validateArgs()
	}

	override fun validateDependency(depId: String) {
		// sbatch will validate the dependency ids, so we don't have to do it here
	}

	override suspend fun makeFoldersAndWriteFiles(folders: List<Path>, files: List<Cluster.FileInfo>) {

		// short circuit, just in case
		if (folders.isEmpty() && files.isEmpty()) {
			return
		}

		sshPool.connection {

			// make folders
			for (path in folders) {
				mkdirs(path)
			}

			// upload files
			sftp {
				for (file in files) {
					uploadText(file.path, file.text)
				}
			}

			// set executable
			for (file in files) {
				if (file.executable) {
					makeExecutable(file.path)
				}
			}
		}
	}

	override suspend fun launch(clusterJob: ClusterJob, depIds: List<String>, scriptPath: Path): ClusterJob.LaunchResult? {

		// build the sbatch command and add any args
		val cmd = ArrayList<String>()
		for (envvar in clusterJob.env) {
			cmd.add("export ${envvar.name}=${envvar.value};")
		}
		cmd.addAll(listOf(
			config.cmdSbatch,
			"--output=\"${clusterJob.outPathMask()}\""
		))

		// WARNING: we're just copying raw command-line args verbatim from pyp's input into our shell commands!
		// There's not really any way we can sanitize them here without knowing anything about them, so we
		// have to just trust that pyp isn't sending us injection attacks.
		cmd.addAll(clusterJob.args
			// empty args break sbatch somehow, so filter them out
			.filter { it.isNotEmpty() }
		)

		if (depIds.isNotEmpty()) {
			cmd.add("--dependency=afterany:${depIds.joinToString(",")}")
		}

		// use the default cpu queue, if none was specified in the args
		val partition = clusterJob.args
			.find { it.startsWith("--partition=") }
			?.split("=")
			?.lastOrNull()
			?.unquote()
		if (partition == null) {
			// that is, if any default queue exists
			config.queues.firstOrNull()
				?.let { cmd.add("--partition=\"${it.sanitizeQuotedArg()}\"") }
		}

		// render the array arguments
		clusterJob.commands.arraySize?.let { arraySize ->
			val bundleInfo = clusterJob.commands.bundleSize
				?.let { "%$it" }
				?: ""
			cmd.add("--array=1-$arraySize$bundleInfo")
		}

		// set the job name, if any
		clusterJob.clusterName?.let {
			cmd.add("--job-name=\"${it.sanitizeQuotedArg()}\"")
		}

		cmd.add("\"$scriptPath\"")

		// finalize the command
		val command = cmd.joinToString(" ")

		// call sbatch on the SLURM host and wait for the job to submit
		val result = sshPool
			.connection {

				// last chance to abort submission before SLURM gets the job
				if (clusterJob.getLog()?.wasCanceled() == true) {
					// don't submit the command
					null
				} else {
					// submit the command to the login node
					exec(command)
				}
			}
			?: return null

		// get the job id from sbatch stdout
		val sbatchId = result.console
			.getOrNull(0)
			?.takeIf { it.startsWith("Submitted batch job ") }
			?.split(" ")
			?.last()
			?.toLongOrNull()
			?: throw ClusterJob.LaunchFailedException("unable to read job id from sbatch output", result.console, command)

		return ClusterJob.LaunchResult(
			sbatchId,
			result.console.joinToString("\n"),
			command
		)
	}

	override suspend fun waitingReason(launchResult: ClusterJob.LaunchResult): String? =
		launchResult.jobId
			?.let { SQueue(sshPool, config).reason(it) }
			?.let { "Job has been submitted to SLURM. The SLURM status is: ${it.name}: ${it.description}" }

	override suspend fun cancel(clusterJobs: List<ClusterJob>) {

		// short circuit, just in case
		if (clusterJobs.isEmpty()) {
			return
		}

		// call scancel to actually cancel the SLURM jobs
		sshPool.connection {
			for (clusterJob in clusterJobs) {

				// get the SLURM job id for this job, if any
				val slurmId = clusterJob.getLogOrThrow().launchResult?.jobId
					?: continue

				exec("${config.cmdScancel} $slurmId")
			}
		}
	}

	override suspend fun jobResult(clusterJob: ClusterJob, arrayIndex: Int?): ClusterJob.Result {

		// read the job output and cleanup the log file
		val outPath = clusterJob.outPath(arrayIndex)
		val out: String? = try {
			sshPool.connection {
				sftp {
					val out = downloadText(outPath)
					delete(outPath)
					out
				}
			}
		} catch (ex: NoRemoteFileException) {
			// the remote log didn't exist... probably the process just didn't write to stdout or stderr?
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

	override suspend fun deleteFiles(files: List<Path>) {

		// short circuit, just in case
		if (files.isEmpty()) {
			return
		}

		sshPool.connection {
			sftp {
				for (path in files) {
					try {
						delete(path)
					} catch (ex: NoRemoteFileException) {
						// delete failed, oh well I guess
					}
				}
			}
		}
	}
}


fun ArgValues.toSbatchArgs(): List<String> =
	ArrayList<String>().apply {
		add("--cpus-per-task=$slurmLaunchCpus")
		add("--mem=${slurmLaunchMemory}G")
		add("--time=\"${slurmLaunchWalltime.sanitizeQuotedArg()}\"") // user-defined string, needs sanitization!!
		slurmLaunchQueue?.let { add("--partition=\"${it.sanitizeQuotedArg()}\"") } // defined by administrators, but could potentially be overridden by users, needs sanitization!
		slurmLaunchAccount?.let { add("--account=\"${it.sanitizeQuotedArg()}\"") } // user-defined string, needs sanitization!!
	}
