package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.mongo.getListOfDocuments
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import java.time.Instant


data class JobRun(
	val jobId: String,
	var status: RunStatus
) {

	fun toDoc() = Document().apply {
		set("jobId", jobId)
		set("status", status.id)
	}

	companion object {

		fun fromDoc(doc: Document) = JobRun(
			jobId = doc.getString("jobId"),
			status = RunStatus[doc.getString("status")]
		)
	}

	fun toData(runId: Int): JobRunData =
		JobRunData(
			jobId,
			status,
			ClusterJob.getByOwner(JobOwner(jobId, runId).toString())
				.map { job -> job to job.getLog() }
				.sortedBy { (_, log) -> log?.history?.firstOrNull()?.timestamp ?: Long.MAX_VALUE }
				.map { (job, log) ->

					val arrayInfo = log
						?.arrayProgress
						?.let {
							ClusterJobData.ArrayInfo(
								size = job.commands.numJobs,
								started = it.numStarted,
								ended = it.numEnded,
								canceled = it.numCanceled,
								failed = it.numFailed
							)
						}

					// pick a representative RunStatus for this cluster job
					val status = if (log?.wasCanceled() == true) {
						RunStatus.Canceled
					} else if (arrayInfo != null) {
						if (arrayInfo.ended >= arrayInfo.started) {
							if (arrayInfo.failed > 0) {
								RunStatus.Failed
							} else {
								RunStatus.Succeeded
							}
						} else if (arrayInfo.started > 0) {
							RunStatus.Running
						} else {
							RunStatus.Waiting
						}
					} else {
						val history = log?.history ?: emptyList()
						if (history.any { it.status == ClusterJob.Status.Ended }) {
							when (log?.result?.type) {
								ClusterJobResultType.Success -> RunStatus.Succeeded
								ClusterJobResultType.Failure -> RunStatus.Failed
								ClusterJobResultType.Canceled -> RunStatus.Canceled
								null -> RunStatus.Failed
							}
						} else if (history.any { it.status == ClusterJob.Status.Abandoned }) {
							RunStatus.Failed
						} else if (history.any { it.status == ClusterJob.Status.Started }) {
							RunStatus.Running
						} else {
							RunStatus.Waiting
						}
					}

					ClusterJobData(job.id!!, job.webName, job.clusterName, status, arrayInfo)
				}
		)
}

data class ProjectRun(
	val timestamp: Instant,
	val jobs: List<JobRun>,
	val runningUserId: String? = null,
	var status: RunStatus,
	var id: Int? = null
) {

	val idOrThrow get() =
		id ?: throw IllegalStateException("project run hasn't been saved yet, so it has no id")

	fun toDoc() = Document().apply {
		set("timestamp", timestamp.toEpochMilli())
		set("jobs", jobs.map { it.toDoc() })
		set("runningUserId", runningUserId)
		set("status", status.id)
	}

	companion object {

		fun fromDoc(doci: Int, doc: Document) = ProjectRun(
			timestamp = Instant.ofEpochMilli(doc.getLong("timestamp")),
			jobs = doc.getListOfDocuments("jobs")
				?.map { JobRun.fromDoc(it) }
				?: emptyList(),
			runningUserId = doc.getString("runningUserId"),
			status = RunStatus[doc.getString("status")],
			id = doci
		)
	}

	fun getJob(jobId: String) =
		jobs
			.find { it.jobId == jobId }
			?: throw java.util.NoSuchElementException("no running job with id=$jobId")

	fun toData(): ProjectRunData =
		ProjectRunData(
			idOrThrow,
			timestamp.toEpochMilli(),
			status,
			jobs.map { it.toData(idOrThrow) }
		)
}
