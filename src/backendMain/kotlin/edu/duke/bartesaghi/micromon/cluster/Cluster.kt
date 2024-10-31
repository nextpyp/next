package edu.duke.bartesaghi.micromon.cluster

import com.mongodb.client.model.Updates
import com.mongodb.client.model.Updates.push
import com.mongodb.client.model.Updates.set
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.slurm.SBatch
import edu.duke.bartesaghi.micromon.cluster.standalone.PseudoCluster
import edu.duke.bartesaghi.micromon.linux.userprocessor.deleteAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.editPermissionsAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.writeStringAs
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.services.ClusterJobResultType
import edu.duke.bartesaghi.micromon.services.ClusterMode
import edu.duke.bartesaghi.micromon.services.ClusterQueues
import edu.duke.bartesaghi.micromon.services.StreamLog
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant


interface Cluster {

	val clusterMode: ClusterMode

	val commandsConfig: Commands.Config

	val queues: ClusterQueues

	/**
	 * Throw an error if the job is somehow not valid.
	 * If the user should be notified about the error, throw a ClusterJob.ValidationFailedException specifically
	 */
	fun validate(job: ClusterJob)

	/**
	 * Throw an error if the dependency id format can't be recognized.
	 * But don't try to check if the dependency exists yet.
	 */
	fun validateDependency(depId: String)

	suspend fun launch(clusterJob: ClusterJob, depIds: List<String>, scriptPath: Path): ClusterJob.LaunchResult?

	suspend fun waitingReason(clusterJob: ClusterJob, launchResult: ClusterJob.LaunchResult): String?

	suspend fun cancel(clusterJobs: List<ClusterJob>)

	suspend fun jobResult(clusterJob: ClusterJob, arrayIndex: Int?): ClusterJob.Result


	companion object {

		val instance: Cluster =
			Config.instance.let { config ->
				// prefer the slurm cluster if available
				if (config.slurm != null) {
					SBatch(config.slurm)
				} else {
					// otherwise, fall back to the pseudo-cluster
					PseudoCluster(config.standalone ?: Config.Standalone())
				}
			}

		val queues: ClusterQueues get() = instance.queues

		suspend fun waitingReason(clusterJob: ClusterJob): String {

			// see if the job is still waiting
			val log = clusterJob.getLog()
				?: return "Unknown reason, no job log was found"
			return when (log.status()) {

				null, ClusterJob.Status.Submitted -> "Job is waiting to submit to the cluster"

				ClusterJob.Status.Launched -> {

					// ask the cluster about the job status
					val launchResult = log.launchResult
						?: return "Unknown reason: the job has been sent to the cluster, but the job's cluster id was lost"
					return instance.waitingReason(clusterJob, launchResult)
						?: "Unknown reason: the job has been sent to the cluster, but the cluster is not aware of this job"
				}

				ClusterJob.Status.Started -> "Job is no longer waiting, it has started"
				ClusterJob.Status.Ended -> "Job is no longer waiting, it has ended"
				ClusterJob.Status.Canceling -> "Job is no longer waiting, it has been canceled"
				ClusterJob.Status.Abandoned -> "Job is no longer waiting, it has been abandoned"
			}
		}

		suspend fun submit(clusterJob: ClusterJob): String? {
			try {
				return submitFragile(clusterJob)
			} catch (t: Throwable) {

				try {
					recoverFailedSubmit(clusterJob, t)
				} catch (t2: Throwable) {
					Backend.log.error("Failed cluster job submission recovery failed too!", t2.cleanupStackTrace())
				}

				// bubble up the original error so callers know about the failure too
				throw t
			}
		}

		private suspend fun recoverFailedSubmit(clusterJob: ClusterJob, t: Throwable) {

			// we don't know quite where the job submission failed, so just try to recover as best we can

			val clusterJobId = clusterJob.id
				?: return

			// we'll never get any future events about this job, so end it now
			Database.instance.cluster.log.update(clusterJobId, null,
				push("history", ClusterJob.HistoryEntry(ClusterJob.Status.Ended).toDBList()),

				// also record error information, wherever we can get it
				when (t) {

					is ClusterJob.LaunchFailedException ->
						set("sbatch", ClusterJob.LaunchResult(
							null,
							t.console.joinToString("\n"),
							t.command
						).toDoc())

					is ClusterJob.ValidationFailedException ->
						set("submitFailure", t.message ?: "(unknown validation reason)")

					else ->
						set("submitFailure", "Internal Error")
				}
			)

			// send events to listeners
			for (listener in ClusterJob.listeners()) {
				listener.onEnd(clusterJob.ownerId, clusterJobId, ClusterJobResultType.Failure)
			}
			clusterJob.commands.arraySize?.let { arraySize ->
				// none of the array jobs will actually start (or end), but send the numbers to the client anyway
				// so the UI does something intelligent instead of showing N unlaunched array jobs
				for (listener in ClusterJob.listeners()) {
					listener.onStartArray(clusterJob.ownerId, clusterJobId, 0, arraySize)
					listener.onEndArray(clusterJob.ownerId, clusterJobId, 0, arraySize, 0, arraySize)
				}
			}
		}

		private suspend fun submitFragile(clusterJob: ClusterJob): String? {

			// create the cluster job id
			if (clusterJob.id != null) {
				throw IllegalStateException("job already submitted")
			}
			val clusterJobId = clusterJob.create()

			// DEBUG
			//println("Cluster.submit() ${job.name}($dbid), array=${job.commands.arraySize}")

			// make sure the owner listener is registered correctly
			if (clusterJob.ownerListener != null && ClusterJob.ownerListeners.find(clusterJob.ownerListener.id) == null) {
				throw IllegalArgumentException("""
					|Owner listener ${clusterJob.ownerListener.id} is not registered with ClusterJob.
					|Add it with ClusterJob.ownerListeners.add().
				""".trimMargin())
			}

			// init the log
			Database.instance.cluster.log.create(clusterJobId) {
				ClusterJob.Log.initDoc(this, ClusterJob.HistoryEntry(ClusterJob.Status.Submitted))
			}

			// send events to listeners
			for (listener in ClusterJob.listeners()) {
				listener.onSubmit(clusterJob)
			}

			// the job launcher checks arguments already,
			// but check them here too, so we can fail earlier
			// but wait until at least after the submission event went out, so failures here can be shown in the UI
			instance.validate(clusterJob)

			// resolve the dependency ids, or die trying
			val depIds = clusterJob.deps
				.map { depId ->

					// Sometimes, pyp puts `_N` on the end of the dependency id to signal a dependency
					// on the Nth element in an array job. The database doesn't know anything about that,
					// so take it off before jobId resolution.
					val (depIdJob, depIdArray) = depId
						.split('_')
						.let { it[0] to it.getOrNull(1) }

					val launchId = Database.instance.cluster.log.get(depIdJob)
						?.let { ClusterJob.Log.fromDoc(it) }
						?.launchResult
						?.jobId
						?: throw IllegalStateException("dependency job with id=$depId not launched yet")

					// But then put the `_N` back on before giving the IDs to the cluster.
					if (depIdArray != null) {
						"${launchId}_$depIdArray"
					} else {
						launchId.toString()
					}
				}

			// validate the dependency ids
			for (depId in depIds) {
				instance.validateDependency(depId)
			}

			// write the script files
			val scriptPath = clusterJob.batchPath()
			fun cmdWebrpc(vararg args: String): String =
				if (Config.instance.pyp.mock != null) {
					Container.MockPyp.cmdWebrpc(*args)
				} else {
					Container.Pyp.cmdWebrpc(*args)
				}
			val commands = clusterJob.commands.render(clusterJob, instance.commandsConfig)
			scriptPath.writeStringAs(clusterJob.osUsername, """
				|#!/bin/bash
				|
				|export NEXTPYP_WEBHOST="${Config.instance.web.webhost}"
				|export NEXTPYP_TOKEN="${JsonRpc.token}"
				|export NEXTPYP_WEBID="$clusterJobId"
				|
				|# cd into any non-home folder before running any singularity commands.
				|# For some weird reason, singularity behaves differently when run from the home folder.
				|# Sometimes it will ignore the --no-home flag and bind the home folder anyway.
				|# Since pyp can be incredibly destructive to folders it runs in,
				|# we never *ever* want pyp to have access to the home folder.
				|cd /tmp || exit 1
				|
				|${cmdWebrpc("slurm_started")}
				|trap "${cmdWebrpc("slurm_ended", "--exit=\\$?").replace("\"", "\\\"")}" exit
				|
				|${commands.joinToString("\n")}
			""".trimMargin())
			scriptPath.editPermissionsAs(clusterJob.osUsername) {
				add(PosixFilePermission.OWNER_EXECUTE)
			}

			// try to launch the job on the cluster
			val launchResult = instance.launch(clusterJob, depIds, scriptPath)
				?: return null

			// launch succeeded, update the database with the launch result
			Database.instance.cluster.log.update(clusterJobId, null,
				push("history", ClusterJob.HistoryEntry(ClusterJob.Status.Launched).toDBList()),
				set("sbatch", launchResult.toDoc())
			)

			return clusterJobId
		}

		suspend fun started(clusterJobId: String, arrayId: Int?) {

			// get the job, if any
			val job = ClusterJob.get(clusterJobId) ?: return

			// DEBUG
			//println("SBatch.started() ${job.name}($dbid), $arrayId")

			// record the job start
			job.pushHistory(ClusterJob.Status.Started, arrayId)

			// update array progress, if needed
			if (arrayId != null) {
				Database.instance.cluster.log.update(clusterJobId, null,
					Updates.inc("arrayProgress.started", 1)
				)
			}

			// is this the first start for this job?
			val log = job.getLogOrThrow()
			val arrayProgress = log.arrayProgress
			if (arrayProgress == null || arrayProgress.numStarted == 1) {

				if (arrayProgress != null) {
					// update the base job too
					job.pushHistory(ClusterJob.Status.Started)
				}

				// yup, send events to listeners
				for (listener in ClusterJob.listeners()) {
					listener.onStart(job.ownerId, clusterJobId)
				}
			}

			// send array events to listeners if needed
			if (arrayProgress != null) {
				for (listener in ClusterJob.listeners()) {
					listener.onStartArray(job.ownerId, clusterJobId, arrayId.arrayIdOrThrow(), arrayProgress.numStarted)
				}
			}
		}

		suspend fun ended(clusterJobId: String, arrayId: Int?, exitCode: Int?) {

			// get the job, if any
			val clusterJob = ClusterJob.get(clusterJobId) ?: return

			// DEBUG
			//println("Cluster.ended() ${clusterJob.webName}(${clusterJob.id}), $arrayId, exitCode=$exitCode")

			// read the job output and cleanup
			// NOTE: this involves potentially slow operations (like SSH)
			//       so do it before updating the database state
			val result = instance.jobResult(clusterJob, arrayId)
				// then apply any failure signals to the cluster job result
				.applyExitCode(exitCode)
				.applyFailures(clusterJob, arrayId)

			// record the job end
			clusterJob.pushHistory(ClusterJob.Status.Ended, arrayId)

			// save the result to the database
			Database.instance.cluster.log.update(clusterJobId, arrayId,
				set("result", result.toDoc())
			)

			// send stream log events, if needed
			if (arrayId == null || arrayId == 1) {
				StreamLog.end(clusterJobId, result)
			}

			// update array progress, if needed
			if (arrayId != null) {
				val updates = mutableListOf(
					Updates.inc("arrayProgress.ended", 1)
				)
				when (result.type) {
					ClusterJobResultType.Success -> Unit
					ClusterJobResultType.Failure -> updates.add(Updates.inc("arrayProgress.failed", 1))
					ClusterJobResultType.Canceled -> updates.add(Updates.inc("arrayProgress.canceled", 1))
				}
				Database.instance.cluster.log.update(clusterJobId, null, updates)
			}

			// is the job all ended?
			val log = clusterJob.getLogOrThrow()
			val progress = log.arrayProgress
			@Suppress("SimplifyBooleanWithConstants")
			val jobAllEnded = false

				// if not an array job
				|| progress == null

				// if all array elements ended
				|| progress.numEnded == clusterJob.commands.arraySize

				// if the job was canceled and all started array elements ended
				|| (log.wasCanceled() && progress.numStarted == progress.numEnded)

			// DEBUG
			//println("\tall ended? jobAllEnded")

			// send array events to listeners, if needed
			if (progress != null) {
				for (listener in ClusterJob.listeners()) {
					listener.onEndArray(clusterJob.ownerId, clusterJobId, arrayId.arrayIdOrThrow(), progress.numEnded, progress.numCanceled, progress.numFailed)
				}
			}

			// if all array jobs ended, update the base job too
			if (arrayId != null && jobAllEnded && log.status() != ClusterJob.Status.Ended) {

				// DEBUG
				//println("\tend main job")

				clusterJob.pushHistory(ClusterJob.Status.Ended)
			}

			if (jobAllEnded) {

				// send events to listeners
				for (listener in ClusterJob.listeners()) {
					listener.onEnd(clusterJob.ownerId, clusterJobId, result.type)
				}

				// cleanup remote files, eventually
				slowIOs {
					clusterJob.batchPath().deleteAs(clusterJob.osUsername)
					for (file in clusterJob.commands.filesToDelete(clusterJob)) {
						file.deleteAs(clusterJob.osUsername)
					}
				}

				// does this have an owner that's listening?
				if (clusterJob.ownerId != null && clusterJob.ownerListener != null) {

					// yup, are all the jobs for this owner ended too?
					val jobs = ClusterJob.getByOwner(clusterJob.ownerId)
					val logs = jobs.associateWith { it.getLog() }
					val endedJobsAndLogs = jobs
						.mapNotNull { job -> logs[job]?.let { job to it } }
						.filter { (_, log) -> log.status() in listOf(ClusterJob.Status.Ended, ClusterJob.Status.Abandoned) }
						// NOTE: assumes only one group of jobs is running per owner at a time

					/* DEBUG
					println("\tall jobs for owner ended? $allOwnerJobsEnded")
					for (job in jobs) {
						println("\t\tjob ${job.name}(${job.dbidOrThrow})  status? ${logs[job]?.status()}")
					}
					*/

					if (endedJobsAndLogs.size >= jobs.size) {

						// the resultType for the owner is the resultType of the most recently-ended cluster job
						val lastJobAndLog = endedJobsAndLogs
							.maxByOrNull { (_, log) ->
								log.history
									.filter { log.status() in listOf(ClusterJob.Status.Ended, ClusterJob.Status.Abandoned) }
									.maxOfOrNull { it.timestamp }
									?: Instant.EPOCH.toEpochMilli()
							}
						val resultType = lastJobAndLog
							?.let { (job, log) ->
								val arraySize = job.commands.arraySize
								if (arraySize != null) {
									// array job: aggregate over the array elements to get result type,
									//            since the log of the non-array entry won't have any results
									(1 .. arraySize)
										.map { i ->
											// NOTE: for large arrays, doing this many database lookups could potentially be slow
											//       do we need to do a more optimized query here?
											job.getLog(i)
												?.result
												?.type
												?: ClusterJobResultType.Failure
										}
										.reduceOrNull { acc, t ->
											// reduction rules: promote cancels and failures over successes
											when (acc) {
												ClusterJobResultType.Success -> when (t) {
													ClusterJobResultType.Success -> acc
													else -> t
												}
												else -> acc
											}
										}
								} else {
									// not an array job: use the log result
									log.result?.type
								}
							}
							?: ClusterJobResultType.Failure

						clusterJob.ownerListener.ended(clusterJob.ownerId, resultType)
					}
				}
			}
		}

		suspend fun cancelAll(ownerId: String): CancelResult {

			// DEBUG
			//println("Cluster.cancel() $ownerId")

			val jobsToCancel = ArrayList<ClusterJob>()
			var waitOnCluster = false

			val clusterJobs = ClusterJob.getByOwner(ownerId)
				.takeIf { it.isNotEmpty() }
				?: return CancelResult.UnknownJob
			for (clusterJob in clusterJobs) {

				val clusterJobId = clusterJob.id ?: continue
				var updateClusterJobStatus: ClusterJob.Status? = null

				val log = clusterJob.getLog()
				when (log?.status()) {

					null, ClusterJob.Status.Submitted -> {

						// tell the job it got canceled
						clusterJob.pushHistory(ClusterJob.Status.Canceling)

						// the cluster doesn't know about this job yet, so end it now
						updateClusterJobStatus = ClusterJob.Status.Ended
					}

					ClusterJob.Status.Launched -> {

						// tell the job it got canceled
						clusterJob.pushHistory(ClusterJob.Status.Canceling)

						jobsToCancel.add(clusterJob)

						// the cluster knows about this job, but we haven't gotten a start signal yet
						// so we won't get an ended signal when we cancel it either
						// so end it now
						updateClusterJobStatus = ClusterJob.Status.Ended
					}

					ClusterJob.Status.Started -> {

						// tell the job it got canceled
						clusterJob.pushHistory(ClusterJob.Status.Canceling)

						jobsToCancel.add(clusterJob)

						// the cluster knows about this job and it was started,
						// which means we'll get an end signal when the cluster cancels it
						waitOnCluster = true
					}

					ClusterJob.Status.Canceling -> {

						// we already tried to cancel the job once,
						// it must not have worked, so just abandon the job entirely
						updateClusterJobStatus = ClusterJob.Status.Abandoned
					}

					ClusterJob.Status.Abandoned, ClusterJob.Status.Ended -> {
						// nothing to do
					}
				}

				if (updateClusterJobStatus != null) {
					clusterJob.pushHistory(updateClusterJobStatus)
					for (listener in ClusterJob.listeners()) {
						listener.onEnd(ownerId, clusterJobId, ClusterJobResultType.Canceled)
					}
				}
			}

			if (jobsToCancel.isNotEmpty()) {
				instance.cancel(jobsToCancel)
			}

			return if (waitOnCluster) {
				CancelResult.CancelRequested
			} else {

				// no job ended signals will show up later, so tell the owner listener we've ended now, if needed
				ClusterJob.ownerListeners.find(ownerId)
					?.ended(ownerId, ClusterJobResultType.Canceled)

				CancelResult.AllCanceled
			}
		}

		fun deleteAll(ownerId: String) {
			for (clusterJob in ClusterJob.getByOwner(ownerId)) {
				clusterJob.delete()
			}
		}

		fun deleteAll(ownerIds: List<String>) {
			for (ownerId in ownerIds) {
				deleteAll(ownerId)
			}
		}
	}

	enum class CancelResult {
		UnknownJob,
		CancelRequested,
		AllCanceled
	}
}

fun Int?.arrayIdOrThrow(): Int =
	this ?: throw NoSuchElementException("Array job has no array id")
