package edu.duke.bartesaghi.micromon.projects

import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.jobs.JobOwner
import edu.duke.bartesaghi.micromon.jobs.JobRunner
import edu.duke.bartesaghi.micromon.services.ClusterJobResultType
import edu.duke.bartesaghi.micromon.services.JobData
import edu.duke.bartesaghi.micromon.services.RunStatus
import java.time.Instant


/**
 * Listens to events from various sources,
 * filters down to a specific project,
 * and forwards events on to project-specific listeners
 */
class ProjectEventListeners {

	interface Listeners {
		suspend fun onRunInit(runId: Int, timestamp: Instant, jobIds: List<String>)
		suspend fun onRunStart(runId: Int)
		suspend fun onJobStart(runId: Int, jobId: String)
		suspend fun onJobUpdate(job: JobData)
		suspend fun onJobFinish(runId: Int, job: JobData, status: RunStatus, errorMessage: String?)
		suspend fun onRunFinish(runId: Int, status: RunStatus)
		suspend fun onClusterJobSubmit(runId: Int, jobId: String, clusterJobId: String, clusterJobWebName: String?, arraySize: Int?)
		suspend fun onClusterJobStart(runId: Int, jobId: String, clusterJobId: String)
		suspend fun onClusterJobStartArray(runId: Int, jobId: String, clusterJobId: String, arrayIndex: Int, numStarted: Int)
		suspend fun onClusterJobFinishArray(runId: Int, jobId: String, clusterJobId: String, arrayIndex: Int, numEnded: Int, numCanceled: Int, numFailed: Int)
		suspend fun onClusterJobFinish(runId: Int, jobId: String, clusterJobId: String, resultType: ClusterJobResultType)
	}

	private class Entry(
		val id: Long,
		val userId: String,
		val projectId: String,
		val listeners: Listeners
	) : JobRunner.Listener, ClusterJob.Listener {

		val runnerId = JobRunner.addListener(this)
		val clusterJobId = ClusterJob.addListener(this)

		fun cleanup() {
			ClusterJob.removeListener(clusterJobId)
			JobRunner.removeListener(runnerId)
		}

		fun matchesProject(userId: String, projectId: String): Boolean {
			return userId == this.userId && projectId == this.projectId
		}

		override suspend fun onInit(userId: String, projectId: String, runId: Int, timestamp: Instant, jobIds: List<String>) {
			if (!matchesProject(userId, projectId)) return
			listeners.onRunInit(runId, timestamp, jobIds)
		}

		override suspend fun onStart(userId: String, projectId: String, runId: Int) {
			if (!matchesProject(userId, projectId)) return
			listeners.onRunStart(runId)
		}

		override suspend fun onStartJob(userId: String, projectId: String, runId: Int, jobId: String) {
			if (!matchesProject(userId, projectId)) return
			listeners.onJobStart(runId, jobId)
		}

		// NOTE: onJobUpdate isn't called by JobRunner.Listener or ClusterJob.Listener
		//       those events come from different sources

		override suspend fun onFinishJob(userId: String, projectId: String, runId: Int, job: JobData, status: RunStatus, errorMessage: String?) {
			if (!matchesProject(userId, projectId)) return
			listeners.onJobFinish(runId, job, status, errorMessage)
		}

		override suspend fun onFinish(userId: String, projectId: String, runId: Int, status: RunStatus) {
			if (!matchesProject(userId, projectId)) return
			listeners.onRunFinish(runId, status)
		}

		private fun String?.toJobOwner(): JobOwner? {

			val jobOwner = JobOwner.fromString(this)
				?: return null

			// filter just to events for this project
			val job = Job.fromId(jobOwner.jobId)
				?: return null
			if (!matchesProject(job.userId, job.projectId)) {
				return null
			}

			return jobOwner
		}

		override suspend fun onSubmit(clusterJob: ClusterJob) {
			val jobOwner = clusterJob.ownerId.toJobOwner() ?: return
			listeners.onClusterJobSubmit(jobOwner.runId, jobOwner.jobId, clusterJob.idOrThrow, clusterJob.webName, clusterJob.commands.arraySize)
		}

		override suspend fun onStart(ownerId: String?, dbid: String) {
			val jobOwner = ownerId.toJobOwner() ?: return
			listeners.onClusterJobStart(jobOwner.runId, jobOwner.jobId, dbid)
		}

		override suspend fun onStartArray(ownerId: String?, dbid: String, arrayId: Int, numStarted: Int) {
			val jobOwner = ownerId.toJobOwner() ?: return
			listeners.onClusterJobStartArray(jobOwner.runId, jobOwner.jobId, dbid, arrayId, numStarted)
		}

		override suspend fun onEndArray(ownerId: String?, dbid: String, arrayId: Int, numEnded: Int, numCanceled: Int, numFailed: Int) {
			val jobOwner = ownerId.toJobOwner() ?: return
			listeners.onClusterJobFinishArray(jobOwner.runId, jobOwner.jobId, dbid, arrayId, numEnded, numCanceled, numFailed)
		}

		override suspend fun onEnd(ownerId: String?, dbid: String, resultType: ClusterJobResultType) {
			val jobOwner = ownerId.toJobOwner() ?: return
			listeners.onClusterJobFinish(jobOwner.runId, jobOwner.jobId, dbid, resultType)
		}
	}

	private var nextId: Long = 0
	private val entries = HashMap<Long,Entry>()

	fun getAll(userId: String, projectId: String): List<Listeners> =
		entries.values
			.filter { it.userId == userId && it.projectId == projectId }
			.map { it.listeners }

	fun add(userId: String, projectId: String, listeners: Listeners): Long {

		val entry = Entry(
			nextId++,
			userId,
			projectId,
			listeners
		)

		entries[entry.id] = entry

		return entry.id
	}

	fun remove(id: Long) {
		val entry = entries.remove(id) ?: return
		entry.cleanup()
	}
}
