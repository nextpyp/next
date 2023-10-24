package edu.duke.bartesaghi.micromon.cluster.standalone

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.Commands
import edu.duke.bartesaghi.micromon.linux.HostProcessor
import edu.duke.bartesaghi.micromon.services.ClusterJobResultType
import edu.duke.bartesaghi.micromon.services.ClusterMode
import edu.duke.bartesaghi.micromon.services.ClusterQueues
import edu.duke.bartesaghi.micromon.services.StandaloneData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.time.Instant
import java.util.*
import kotlin.collections.HashMap
import kotlin.io.path.isRegularFile
import kotlin.math.absoluteValue
import kotlin.random.Random


/**
 * Not a real cluster, but acts like one, using just the resources of the local machine.
 */
class PseudoCluster(val config: Config.Standalone) : Cluster {

	override val clusterMode = ClusterMode.Standalone

	private inner class Job(
		val jobId: Long,
		val clusterJob: ClusterJob,
		val depIds: List<String>,
		val scriptPath: Path
	) {

		val created = Instant.now()

		// parse the arguments
		val numCpus: Int? =
			clusterJob.args
				.firstOrNull { it.startsWith("--cpus-per-task=") }
				?.let { it.split("=")[1].toIntOrNull() }

		// make the tasks
		val tasks: List<Task> =
			clusterJob.commands.arraySize
				.let { arraySize ->
					if (arraySize != null) {
						// for array jobs, make one task for each array element
						// NOTE: use 1-based indexing for the arrayId to match SLURM behavior, since PYP expects that
						(0 until arraySize).map { i -> Task(i, i + 1) }
					} else {
						// for non-array jobs, just make one task overall
						listOf(Task(0, null))
					}
				}
		val tasksLookup: Map<Int?,Task> =
			tasks.associateBy { it.arrayId }

		var canceled = false

		fun waitingReason(jobs: Jobs): String {

			// if the first task was started, then we're not waiting
			val firstTask = tasks.first()
			if (firstTask.wasStarted()) {
				return "The job has been started"
			}

			// otherwise, look at its waiting reason
			firstTask.waitingReason(jobs)
				?.let { return it.msg }

			// first task is not waiting, it should be startable
			return "The job has not been started yet, but could be started at any time"
		}

		suspend fun cancel() {

			canceled = true

			for (task in tasks) {
				task.cancel()
			}
		}


		inner class Task(val taskId: Int, val arrayId: Int?) {

			val job: Job get() = this@Job

			var pid: Long? = null
			var finished: Boolean = false

			val outPath = clusterJob.outPath(arrayId)
			val resourceReservations = ArrayList<Resource.Reservation>()

			fun waitingReason(jobs: Jobs): WaitingReason? {

				// see if the dependencies are finished
				for (depId in job.depIds) {
					if (!dependencySatisfied(jobs, depId)) {
						return WaitingReason.Dependency
					}
				}

				// check resource limits
				val numCpus = job.numCpus
				if (numCpus != null) {
					if (jobs.resources[Resource.Type.Cpu]?.isAvailable(numCpus) != true) {
						return WaitingReason.Resources
					}
				}

				// no reason to wait
				return null
			}

			private fun dependencySatisfied(jobs: Jobs, depId: String): Boolean {

				val (depIdJob, depIdArray) = depId
					.split('_')
					.let { it[0].toLong() to it.getOrNull(1)?.toInt() }

				// if the job is finished, the dependency is satisfied
				if (depIdJob in jobs.finishedJobIds) {
					return true
				}

				if (depIdArray != null) {

					// if the array task is finished, the dependency is satisfied
					val job = jobs[depIdJob]
						?: return false
					val task = job.tasksLookup[depIdArray]
						?: return false
						// NOTE: if the task can't be found, this dependency can never be satisfied
					if (task.finished) {
						return true
					}
				}

				// not satisfied
				return false
			}

			suspend fun start() {

				val commands = ArrayList<String>()

				// emulate the SLURM environment variables
				// they're used in a ton of places in pyp
				commands.add("SLURM_SUBMIT_DIR=${clusterJob.dir}")
				commands.add("SLURM_JOB_ID=${job.jobId}")
				if (arrayId != null) {
					commands.add("SLURM_ARRAY_JOB_ID=${job.jobId}")
					commands.add("SLURM_ARRAY_TASK_ID=$arrayId")
				}
				if (numCpus != null) {
					commands.add("SLURM_CPUS_PER_TASK=$numCpus")
				}

				// add the job environment variables
				for (envvar in clusterJob.env) {
					commands.add("${envvar.name}=${envvar.value}")
				}

				// add the path to the script
				commands.add(scriptPath.toString())

				pid = HostProcessor.exec(commands.joinToString(" "), outPath)
			}

			fun wasStarted(): Boolean {
				return pid != null
			}

			suspend fun cancel() {
				val pid = pid ?: return
				HostProcessor.kill(pid)
				// TODO: do we need to forcibly terminate? after a timeout?
			}
		}
	}

	private enum class WaitingReason(val msg: String) {
		Dependency("The job is waiting for another job to finish"),
		Resources("The job is waiting for more resources to become available")
	}

	private class Resource(val type: Type, val total: Int) {

		enum class Type {
			Cpu
		}

		data class Reservation(
			val type: Type,
			val num: Int
		)

		var available: Int = total

		fun isAvailable(num: Int): Boolean =
			available >= num

		fun reserve(num: Int): Reservation {
			available -= num
			return Reservation(type, num)
		}

		fun release(reservation: Reservation) {

			// just in case ...
			if (reservation.type != this.type) {
				throw IllegalArgumentException("can't release ${reservation.type} reservation from the $type resource")
			}

			available += reservation.num
		}
	}

	private inner class Jobs {

		private val _jobs = HashMap<Long,Job>()
		val jobs: Map<Long,Job> get() = _jobs

		private val _finishedJobIds = HashSet<Long>()
		val finishedJobIds: Set<Long> get() = _finishedJobIds

		private val _tasksWaiting = LinkedHashSet<Job.Task>()
		val tasksWaiting: Set<Job.Task> get() = _tasksWaiting

		private val _tasksRunning = HashSet<Job.Task>()
		val tasksRunning: Set<Job.Task> get() = _tasksRunning

		val resources = listOf(
			Resource(Resource.Type.Cpu, config.availableCpus)
			// TODO: memory?
		).associateBy { it.type }


		fun nextId(): Long {

			for (i in 0 until 100) {
				val id = Random.nextLong().absoluteValue
				if (!_jobs.containsKey(id)) {
					return id
				}
			}

			// this is very unlikely to ever happen
			throw Error("Ran out of launch ids (probalistically)")
		}

		fun add(job: Job): Job {

			// add the job
			_jobs[job.jobId] = job

			// add the job's tasks
			_tasksWaiting.addAll(job.tasks)

			return job
		}

		operator fun get(jobId: Long): Job? =
			_jobs[jobId]

		fun waitingReason(jobId: Long): String? {

			if (jobId in _finishedJobIds) {
				return "The job has finished"
			}

			return _jobs[jobId]?.waitingReason(this)
		}

		suspend fun maybeStartTasks() {
			while (true) {

				// see if the next task is runnable
				val task = _tasksWaiting
					.firstOrNull()
					?.takeIf { it.waitingReason(this) == null }
					?: break

				// yup, we got one

				// reserve resources for the task, if needed
				val numCpus = task.job.numCpus
				if (numCpus != null) {
					task.resourceReservations.add(resources.getValue(Resource.Type.Cpu).reserve(numCpus))
				}

				// start the task
				task.start()
				_tasksWaiting.remove(task)
				_tasksRunning.add(task)
			}
		}

		suspend fun taskFinished(task: Job.Task) {

			// cleanup the task
			task.finished = true
			_tasksRunning.remove(task)

			// release any task resources
			for (reservation in task.resourceReservations) {
				resources[reservation.type]?.release(reservation)
			}
			task.resourceReservations.clear()

			// cleanup the job, if needed
			if (task.job.tasks.all { it.finished }) {
				_jobs.remove(task.job.jobId)
				_finishedJobIds.add(task.job.jobId)
			}

			// keep going
			maybeStartTasks()
		}
	}

	private inner class JobsGuard {

		val jobs = Jobs()
		val mutex = Mutex()

		suspend inline fun <R> use(block: (Jobs) -> R): R =
			mutex.withLock {
				block(jobs)
			}
	}

	private val jobs = JobsGuard()


	override val commandsConfig: Commands.Config get() =
		config.commandsConfig

	override val queues: ClusterQueues =
		ClusterQueues(emptyList(), emptyList())

	override fun validate(job: ClusterJob) {
		// all jobs are valid for now
	}

	override fun validateDependency(depId: String) {

		val parts = depId.split('_')
		when (parts.size) {

			1 -> {
				parts[0].toLongOrNull()
					?: throw IllegalArgumentException("dependency id $depId is not a number")
			}

			2 -> {
				parts[0].toLongOrNull()
					?: throw IllegalArgumentException("dependency id $depId job part ${parts[0]} is not a number")
				parts[1].toIntOrNull()
					?: throw IllegalArgumentException("dependency id $depId array part ${parts[1]} is not an integer")
			}

			else -> throw IllegalArgumentException("dependency id $depId is unrecognizable")
		}
	}

	override suspend fun makeFoldersAndWriteFiles(folders: List<Path>, files: List<Cluster.FileInfo>) {

		// just write to the local filesystem
		for (folder in folders) {
			folder.createDirsIfNeeded()
		}

		for (file in files) {
			file.path.writeString(file.text)
			if (file.executable) {
				file.path.makeExecutable()
			}
		}
	}

	override suspend fun launch(
		clusterJob: ClusterJob,
		depIds: List<String>,
		scriptPath: Path
	): ClusterJob.LaunchResult {

		// create the job, and start it, if possible
		val job = jobs.use { jobs ->
			val job = Job(jobs.nextId(), clusterJob, depIds, scriptPath)
			jobs.add(job)
			jobs.maybeStartTasks()
			return@use job
		}

		return ClusterJob.LaunchResult(job.jobId, "")
	}

	private suspend fun ClusterJob.findJob(): Job? =
		 getLog()
			?.launchResult
			?.jobId
			?.let { jobId ->
				jobs.use { jobs ->
					jobs[jobId]
				}
			}

	override suspend fun waitingReason(launchResult: ClusterJob.LaunchResult): String? {
		jobs.use { jobs ->
			return launchResult.jobId?.let { jobs.waitingReason(it) }
		}
	}

	override suspend fun cancel(clusterJobs: List<ClusterJob>) {
		for (clusterJob in clusterJobs) {
			clusterJob.findJob()?.cancel()
		}
	}

	override suspend fun jobResult(clusterJob: ClusterJob, arrayIndex: Int?): ClusterJob.Result {

		// read the output file and delete it
		val outPath = clusterJob.outPath(arrayIndex)
		val out = outPath
			.takeIf { it.isRegularFile() }
			?.let { path -> path.readString().also { path.delete() } }

		// find the job and task, if possible
		val job = clusterJob.findJob()
			// no launch available, the server must have been restarted while a process was running
			// there's nothing to cleanup, so just pretend it was successful
			?: return ClusterJob.Result(ClusterJobResultType.Success, out)
		val task = job.tasksLookup[arrayIndex]
			?: throw NoSuchElementException("task for array=$arrayIndex wasn't found in job ${job.jobId}")

		// clean up the job, and continue the next one, if possible
		jobs.use { jobs ->
			jobs.taskFinished(task)
		}

		// hooray! the job was successful
		val result = when (job.canceled) {
			true -> ClusterJobResultType.Canceled
			false -> ClusterJobResultType.Success
		}
		return ClusterJob.Result(result, out)
	}

	override suspend fun deleteFiles(files: List<Path>) {

		// just use the local filesystem
		for (file in files) {
			file.delete()
		}
	}

	suspend fun state(): StandaloneData =
		jobs.use { jobs ->
			StandaloneData(

				// get the state of resources
				resources = jobs.resources.entries
					.map { (type, resource) ->
						StandaloneData.Resource(
							name = type.name,
							available = resource.available,
							total = resource.total
						)
					}
					.sortedBy { it.name },

				jobs = jobs.jobs.values
					.toList()
					.sortedBy { it.created }
					.map { job ->
						StandaloneData.Job(
							standaloneId = job.jobId,
							clusterId = job.clusterJob.idOrThrow,
							ownerId = job.clusterJob.ownerId,
							name = job.clusterJob.clusterName,
							resources = HashMap<String,Int>().apply {
								job.numCpus?.let { this[Resource.Type.Cpu.name] = it }
							},
							tasks = job.tasks.map { task ->
								StandaloneData.Task(
									taskId = task.taskId,
									arrayId = task.arrayId,
									resources = task.resourceReservations.associate { reservation ->
										reservation.type.name to reservation.num
									},
									pid = task.pid,
									waitingReason = task.waitingReason(jobs)?.name,
									finished = task.finished
								)
							},
							waitingReason = job.waitingReason(jobs),
							canceled = job.canceled
						)
					},

				tasksRunning = jobs.tasksRunning
					.sortedWith(
						compareBy<Job.Task> { it.job.created }
							.thenBy { it.taskId }
					)
					.map { task ->
						StandaloneData.TaskRef(task.job.jobId, task.taskId)
					},

				tasksWaiting = jobs.tasksWaiting
					// NOTE: this is a queue, so don't sort it
					.map { task ->
						StandaloneData.TaskRef(task.job.jobId, task.taskId)
					}
			)
		}
}
