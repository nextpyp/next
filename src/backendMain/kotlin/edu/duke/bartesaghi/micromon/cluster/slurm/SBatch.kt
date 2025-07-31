package edu.duke.bartesaghi.micromon.cluster.slurm

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.Commands
import edu.duke.bartesaghi.micromon.linux.Command
import edu.duke.bartesaghi.micromon.linux.CommandExecutor
import edu.duke.bartesaghi.micromon.linux.LocalCommandExecutor
import edu.duke.bartesaghi.micromon.linux.userprocessor.deleteAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.readStringAs
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.ClusterJobResultType
import edu.duke.bartesaghi.micromon.services.ClusterMode
import java.nio.file.Path


class SBatch(val config: Config.Slurm) : Cluster {

	companion object {

		val BANNED_ARGS = setOf(
			"output",
			"error",
			"chdir",
			"array"
		)

		val SUPPORTED_ARGS = setOf(
			"cpus-per-task",
			"mem",
			"time",
			"gres"
		)
	}

	override val clusterMode = ClusterMode.SLURM

	override val commandsConfig: Commands.Config get() =
		config.commandsConfig

	// submit jobs over SSH if a SLURM host is configured,
	// otherwise submit locally using the host processor
	private val commandExecutor: CommandExecutor =
		config.host
			?.let {
				SSHPool(SshPoolConfig(
					config.user,
					it,
					config.key,
					config.port,
					config.maxConnections,
					config.timeoutSeconds
				))
			}
			?: LocalCommandExecutor()

	override fun validate(job: ClusterJob) {

		val args = job.argsParsed

		// look for any banned arguments
		BANNED_ARGS
			.filter { bannedName -> args.any { (name, _) -> name == bannedName } }
			.takeIf { it.isNotEmpty() }
			?.let { found ->
				throw IllegalArgumentException("The sbatch argument(s) $found are handled automatically by nextPYP, no need to specify them explicitly")
			}

		// look for any unsupported arguments
		args
			.filter { (name, _) -> name !in SUPPORTED_ARGS }
			.takeIf { it.isNotEmpty() }
			?.let { found ->
				throw IllegalArgumentException("unsupported sbatch argument(s): $found\nMicromon must be updated to support these arguments.")
			}

		// these are errors caused by users (rather than programmers),
		// so remap the error types so the errors can be shown in the UI
		try {

			// validate any gres arguments
			args.filter { (name, _) -> name == "gres" }
				.forEach { (_, value) -> Gres.parseAll(value) }

		} catch (t: Throwable) {
			throw ClusterJob.ValidationFailedException(t)
		}
	}

	override suspend fun buildScript(clusterJob: ClusterJob, commands: String): String {

		val templates = TemplateEngine(config)
		val template = templates.templateOrThrow(clusterJob.template)

		// set args from the user
		clusterJob.osUsername?.let {
			template.args.user["os_username"] = it
		}
		clusterJob.userProperties?.let {
			template.args.user["properties"] = it
		}

		// set args from the job
		for ((name, value) in clusterJob.argsParsed) {
			if (name != null) {
				template.args.job[name] = value
			}
		}

		// handle cluster job dependencies
		val deps = clusterJob.dependencies()
		if (deps.isNotEmpty()) {
			val launchIds = deps.joinToString(",") { it.launchIdOrThrow }
			template.args.job["dependency"] = "afterany:$launchIds"
		}

		// render the array arguments
		clusterJob.commands.arraySize?.let { arraySize ->
			val bundleInfo = clusterJob.commands.bundleSize
				?.let { "%$it" }
				?: ""
			template.args.job["array"] = "1-$arraySize$bundleInfo"
		}

		// set the job name, if any
		clusterJob.clusterName?.let {
			template.args.job["name"] = it
		}

		// set the job type, if any
		clusterJob.type?.let {
			template.args.job["type"] = it
		}

		template.args.job["commands"] = commands

		return templates.eval(template)
	}

	override suspend fun launch(clusterJob: ClusterJob, scriptPath: Path): ClusterJob.LaunchResult? {

		// build the sbatch command and add any args
		// NOTE: argument sanitization is handled by Command.toSafeShellString(), so we don't need to double-sanitize each argument here
		var sbatch = Command(config.cmdSbatch)
		sbatch.args.add("--output=${clusterJob.outPathMask()}")
		sbatch.args.add("$scriptPath")

		// run the job as the specified OS user, if needed
		if (clusterJob.osUsername != null) {
			sbatch = Backend.instance.userProcessors.get(clusterJob.osUsername).wrap(sbatch)
		}

		// add environment vars from the job submission
		sbatch.envvars.addAll(clusterJob.env)

		// call sbatch on the SLURM host and wait for the job to submit
		val result = commandExecutor
			.connection {

				// last chance to abort submission before SLURM gets the job
				if (clusterJob.getLog()?.wasCanceled() == true) {
					// don't submit the command
					null
				} else {
					// submit the command to the login node
					exec(sbatch)
				}
			}
			?: return null

		// get the job id from sbatch stdout
		val sbatchId = result.console
			.lastOrNull { it.isNotBlank() }
			// NOTE: the user-processor can add messages before the sbatch output,
			//       so just look at the last line of the console output
			?.takeIf { it.trim().startsWith("Submitted batch job ") }
			?.trim()
			?.split(" ")
			?.last()
			?.toLongOrNull()
			?: throw ClusterJob.LaunchFailedException("unable to read job id from sbatch output", result.console, sbatch.toShellSafeString())

		return ClusterJob.LaunchResult(
			sbatchId,
			result.console.joinToString("\n"),
			sbatch.toShellSafeString()
		)
	}

	override suspend fun waitingReason(clusterJob: ClusterJob, launchResult: ClusterJob.LaunchResult): String? =
		launchResult.jobId
			?.let { SQueue(commandExecutor, config, clusterJob.osUsername).reason(it) }
			?.let { "Job has been submitted to SLURM. The SLURM status is: ${it.name}: ${it.description}" }

	override suspend fun cancel(clusterJobs: List<ClusterJob>) {

		// short circuit, just in case
		if (clusterJobs.isEmpty()) {
			return
		}

		// build the cancel commands
		val launchedClusterJobs = clusterJobs
			.mapNotNull f@{ clusterJob ->
				// get the SLURM job id for this job, if any
				val slurmId = clusterJob.getLogOrThrow().launchResult?.jobId
					?: return@f null
				clusterJob to slurmId
			}

		val commands = launchedClusterJobs.map { (clusterJob, slurmId) ->

			// build the scancel command
			var scancel = Command(config.cmdScancel, slurmId.toString())

			// run the job as the specified OS user, if needed
			if (clusterJob.osUsername != null) {
				scancel = Backend.instance.userProcessors.get(clusterJob.osUsername).wrap(scancel)
			}

			scancel
		}

		for ((clusterJob, slurmId) in launchedClusterJobs) {
			Backend.log.debug("calling scancel clusterJob={}  SLURMid={} ...", clusterJob.id, slurmId)
		}

		// run the commands on the SLURM login node
		val results = commandExecutor.connection {
			commands.map { cmd ->
				exec(cmd)
			}
		}

		// record the result of the scancel
		for ((launchedClusterJob, result) in launchedClusterJobs.zip(results)) {
			val (clusterJob, slurmId) = launchedClusterJob
			Backend.log.debug("scancel result (clusterJob={}, SLURMid={})  exit={}  console:\n\t{}",
				clusterJob.id, slurmId, result.exitCode, result.console.joinToString("\n\t")
			)
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
		add("--mem=${slurmLaunchCpus*slurmLaunchMemory}G")
		add("--time=$slurmLaunchWalltime")
		slurmLaunchGres?.let { add("--gres=$it") }
	}
	// NOTE: argument sanitization is handled by the job submitter, so we don't need to double-sanitize each argument here
