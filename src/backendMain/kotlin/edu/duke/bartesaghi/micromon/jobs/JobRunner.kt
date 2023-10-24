package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.projects.Project
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.services.ClusterJobResultType
import edu.duke.bartesaghi.micromon.services.JobData
import edu.duke.bartesaghi.micromon.services.RunStatus
import edu.duke.bartesaghi.micromon.uniqueKey
import java.time.Instant
import java.util.*


/**
 * Runs jobs for a project and keeps track of their progress/status
 */
class JobRunner(val project: Project) {

	constructor (userId: String, projectId: String) : this(Database.projects.getProjectOrThrow(userId, projectId))

	private fun isRunning(): Boolean =
		project.getRuns()
			.any { it.status == RunStatus.Running }

	/**
	 * Initializes a new run, adds it to the running queue for the project
	 */
	suspend fun init(jobIds: List<String>) {

		// get the jobs in sorted order for processing
		val jobs = jobIds
			.map { Job.fromIdOrThrow(it) }
			.let { project.downstreamInfo().topoSort(it) }

		// save this job order to the project
		val run = ProjectRun(
			timestamp = Instant.now(),
			jobs = jobs.map {
				JobRun(it.idOrThrow, RunStatus.Waiting)
			},
			status = RunStatus.Waiting
		)
		val runId = project.pushRun(run)

		// notify listeners
		for (listener in listeners.values) {
			listener.onInit(project.userId, project.projectId, runId, run.timestamp, jobs.map { it.idOrThrow })
		}

		// if a job isn't already running, try to start one
		if (!isRunning()) {
			tryRunNextJob()
		}
	}

	/**
	 * Runs the next availiable job in the queue, if any.
	 * Will start project runs as needed.
	 * Project runs will only be finished if they have no jobs at all.
	 * Otherwise finishRun() must be used to finish the run.
	 */
	private suspend fun tryRunNextJob() {

		// get the next project run, if any
		val run = project.getRuns()
			.firstOrNull { it.status == RunStatus.Waiting || it.status == RunStatus.Running }
			?: return

		// should we start a new project run?
		if (run.status == RunStatus.Waiting) {

			// yup, update the project run status
			run.status = RunStatus.Running
			project.updateRun(run)

			// notify listeners
			for (listener in listeners.values) {
				listener.onStart(project.userId, project.projectId, run.idOrThrow)
			}
		}

		// if this run already has a running job, there's nothing to do here
		if (run.jobs.any { it.status == RunStatus.Running }) {
			return
		}

		// get the next waiting job, if any
		val job = run.jobs
			.firstOrNull { it.status == RunStatus.Waiting }
			?.let { Job.fromIdOrThrow(it.jobId) }
			?: run {

				// this run is running, but has no running or waiting jobs
				// that must mean it has no jobs at all, so finish it now and move to the next run
				finishRun(run)
				tryRunNextJob()
				return
			}

		// mark this job and all downstream jobs as stale
		val jobsToUpdate = mutableListOf(job)
		jobsToUpdate += project.downstreamInfo().jobsIterative(jobsToUpdate)
		for (j in jobsToUpdate) {
			if (!j.stale) {
				j.stale = true
				j.update()
			}
		}

		// mark the job as running
		run.getJob(job.idOrThrow).status = RunStatus.Running
		project.updateRun(run)

		// notify listeners
		for (listener in listeners.values) {
			listener.onStartJob(project.userId, project.projectId, run.idOrThrow, job.idOrThrow)
		}

		// finally, actually launch the job
		job.launch(run.idOrThrow)
	}

	private suspend fun finishRun(run: ProjectRun) {

		// update the project run status
		run.status = if (run.jobs.any { it.status == RunStatus.Canceled }) {
			RunStatus.Canceled
		} else if (run.jobs.any { it.status == RunStatus.Failed }) {
			RunStatus.Failed
		} else {
			RunStatus.Succeeded
		}
		project.updateRun(run)

		// notify listeners
		for (listener in listeners.values) {
			listener.onFinish(project.userId, project.projectId, run.idOrThrow, run.status)
		}
	}

	/**
	 * Call this when a job is finished running.
	 * Finishes the job run, and the project run too if needed.
	 * Tries to run the next job after finish tasks are complete.
	 */
	suspend fun finished(runId: Int, jobId: String, status: RunStatus) {

		val run = project.getRun(runId)
			?: run {
				Backend.log.warn("finished job $jobId, but can't find run $runId in project ${project.projectId}")
				return
			}

		val jobRun = run.getJob(jobId)

		// only finish running jobs
		if (jobRun.status != RunStatus.Running) {
			println("WARNING: tried to finish a job that wasn't running: $runId, $jobId, $status")
			return
		}

		// update the job status
		jobRun.status = status
		project.updateRun(run)

		// if the job wasn't canceled, remove any stale status
		val job = Job.fromIdOrThrow(jobId)
		if (status == RunStatus.Succeeded) {
			job.stale = false
			job.update()

			// and execute any post-run job actions
			job.finished()
		}

		// get the new job data
		val jobData = job.data()

		// notify listeners
		for (listener in listeners.values) {
			listener.onFinishJob(project.userId, project.projectId, run.idOrThrow, jobData, status)
		}

		// finish the run if needed
		if (run.jobs.none { it.status == RunStatus.Waiting }) {
			finishRun(run)
		}

		tryRunNextJob()
	}

	suspend fun cancel(runId: Int) {

		val run = project.getRun(runId)
			?: run {
				Backend.log.warn("trying to cancel, but can't find run $runId in project ${project.projectId}")
				return
			}

		// cancel waiting jobs right now
		for (job in run.jobs) {
			if (job.status == RunStatus.Waiting) {

				// cancel the job
				job.status = RunStatus.Canceled
				project.updateRun(run)

				// get the job data
				val jobData = Job.fromIdOrThrow(job.jobId).data()

				// notify listeners
				for (listener in listeners.values) {
					listener.onFinishJob(project.userId, project.projectId, run.idOrThrow, jobData, job.status)
				}
			}
		}

		// ask any running jobs to cancel
		val jobsToFinish = ArrayList<JobRun>()
		var waitingForJobs = false
		for (job in run.jobs) {
			if (job.status == RunStatus.Running) {
				when (Job.fromIdOrThrow(job.jobId).cancel(run.idOrThrow)) {

					Cluster.CancelResult.UnknownJob -> {
						// the cluster doesn't know about this job, so finish it right after we're done here
						jobsToFinish.add(job)
					}

					Cluster.CancelResult.AllCanceled -> {
						// the cluster says it's all canceled, so finish it right after we're done here
						jobsToFinish.add(job)
					}

					Cluster.CancelResult.CancelRequested -> {
						// don't finish this job now, finished() should get called in the future
						waitingForJobs = true
					}
				}
			}
		}

		// finish any jobs now that aren't running on the cluster, since they won't get finish events later
		for (job in jobsToFinish) {
			finished(runId, job.jobId, RunStatus.Canceled)
		}

		// if we didn't finish any jobs now, and we're not waiting for any jobs to finish later,
		// that means finishRun() will never get called for this run, so call it now,
		// then try to run the next job
		if (jobsToFinish.isEmpty() && !waitingForJobs) {
			finishRun(run)
			tryRunNextJob()
		}

		// otherwise, wait for the finished() calls to come in
	}


	interface Listener {
		suspend fun onInit(userId: String, projectId: String, runId: Int, timestamp: Instant, jobIds: List<String>)
		suspend fun onStart(userId: String, projectId: String, runId: Int)
		suspend fun onStartJob(userId: String, projectId: String, runId: Int, jobId: String)
		suspend fun onFinishJob(userId: String, projectId: String, runId: Int, job: JobData, status: RunStatus)
		suspend fun onFinish(userId: String, projectId: String, runId: Int, status: RunStatus)
	}

	companion object : ClusterJob.OwnerListener {

		override val id = "job"

		fun init() {
			// register as an ClusterJob owner listener
			ClusterJob.ownerListeners.add(this)
		}

		private val listeners = HashMap<Long,Listener>()

		/**
		 * Listen in on all the ClusterJob traffic, for curiosity's sake.
		 * It's not crucial that any particular event is received by anyone in particular.
		 */
		fun addListener(listener: Listener): Long {
			val id = listeners.uniqueKey()
			listeners[id] = listener
			return id
		}

		fun removeListener(id: Long) {
			listeners.remove(id)
		}

		override suspend fun ended(ownerId: String, resultType: ClusterJobResultType) {

			val owner = JobOwner.fromString(ownerId) ?: return
			val job = Job.fromId(owner.jobId) ?: return
			val status = when (resultType) {
				ClusterJobResultType.Success -> RunStatus.Succeeded
				ClusterJobResultType.Failure -> RunStatus.Failed
				ClusterJobResultType.Canceled -> RunStatus.Canceled
			}

			JobRunner(job.userId, job.projectId).finished(owner.runId, job.idOrThrow, status)
		}
	}
}
