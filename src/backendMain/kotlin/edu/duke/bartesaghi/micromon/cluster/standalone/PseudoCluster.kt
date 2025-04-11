package edu.duke.bartesaghi.micromon.cluster.standalone

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.Commands
import edu.duke.bartesaghi.micromon.cluster.slurm.Gres
import edu.duke.bartesaghi.micromon.linux.Command
import edu.duke.bartesaghi.micromon.linux.EnvVar
import edu.duke.bartesaghi.micromon.linux.Posix
import edu.duke.bartesaghi.micromon.linux.hostprocessor.HostProcessor
import edu.duke.bartesaghi.micromon.linux.userprocessor.deleteAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.readStringAs
import edu.duke.bartesaghi.micromon.services.ClusterJobResultType
import edu.duke.bartesaghi.micromon.services.ClusterMode
import edu.duke.bartesaghi.micromon.services.StandaloneData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.time.Instant
import java.util.*
import kotlin.collections.HashMap
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds


/**
 * Not a real cluster, but acts like one, using just the resources of the local machine.
 */
class PseudoCluster(val config: Config.Standalone) : Cluster {

	override val clusterMode = ClusterMode.Standalone

	private inner class Job(
		val jobId: Long,
		val clusterJob: ClusterJob,
		val scriptPath: Path
	) {

		val created = Instant.now()!!

		// parse the arguments
		private val args = clusterJob.argsParsed

		// CPU arguments will look like: --cpus-per-task=5
		val numCpus: Int? =
			args
				.firstOrNull { (name, _) -> name == "cpus-per-task" }
				?.let { (_, value) ->
					when (val i = value.toIntOrNull()) {
						null -> {
							Backend.log.warn("--cpus-per-task value unrecognizable: $value")
							null
						}
						else -> i
					}
				}

		// memory arguments will look like: --mem=5G
		val memGiB: Int? =
			args
				.firstOrNull { (name, _) -> name == "mem" }
				?.let { (_, value) ->
					if (value.endsWith('G') || value.endsWith('g')) {
						value.substring(0, value.length - 1)
							.toIntOrNull()
					} else {
						Backend.log.warn("--mem value unrecognizable: $value")
						null
					}
				}

		// generic resources (including GPU) arguments will look like:
		// --gres=gpu:1
		// --gres=foo:bar,cow:moo:5g
		val numGpus: Int? =
			args
				.firstOrNull { (name, _) -> name == "gres" }
				?.let { (_, value) -> Gres.parseAll(value) }
				?.firstOrNull { it.isGpu }
				?.count
				?.expand()
				?.let { count ->
					if (count <= Int.MAX_VALUE) {
						count.toInt()
					} else {
						// unlikely to ever happen in the real world,
						// but someone could easily request 6G gpus if they really wanted ...
						throw IllegalArgumentException("number of GPUs requested overflows int: $count")
					}
				}

		val requestedResources: List<Pair<Int,Resource.Type>> =
			ArrayList<Pair<Int,Resource.Type>>().apply {
				numCpus?.let { add(it to Resource.Type.Cpu) }
				memGiB?.let { add(it to Resource.Type.Memory) }
				numGpus?.let { add(it to Resource.Type.Gpu) }
			}

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
			if (firstTask.started) {
				val process = firstTask.process
				return if (process != null) {
					"The job is running under PID ${process.pid}"
				} else {
					"The job has been submitted and should start running soon"
				}
			}

			// otherwise, look at its waiting reason
			firstTask.waitingReason(jobs)
				?.let { return it.msg }

			// we shouldn't be able to query the waiting reason on a canceled job, but just in case ...
			if (canceled) {
				return "The job has been canceled and will not start"
			}

			// no reason the job shouldn't have started yet
			// something interesting is going on if we ever get here
			return "The job has not been started yet, but is elligible to be started"
		}


		inner class Task(val taskId: Int, val arrayId: Int?) {

			val job: Job get() = this@Job

			var started: Boolean = false
			var process: HostProcessor.Process? = null
			var finished: Boolean = false
			var canceled: Boolean = false

			val outPath = clusterJob.outPath(arrayId)
			val resourceReservations = ArrayList<Resource.Reservation>()
			val reservedGpus = ArrayList<Int>()

			fun waitingReason(jobs: Jobs): WaitingReason? {

				// if we already started, we're not waiting
				if (started) {
					return null
				}

				// see if the dependencies are finished
				for (dep in job.clusterJob.dependencies()) {
					if (!dependencySatisfied(jobs, dep)) {
						return WaitingReason.Dependency
					}
				}

				// check resource limits
				for ((requested, type) in requestedResources) {
					if (!jobs.resource(type).isAvailable(requested)) {
						return WaitingReason.Resources
					}
				}

				// no reason to wait
				return null
			}

			private fun dependencySatisfied(jobs: Jobs, dep: ClusterJob.Dependency): Boolean {

				val launchId = dep.launchId
					// dependency not launched yet, so the dependency is not satisfied
					?: return false

				val (depIdJob, depIdArray) = launchId
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

				started = true

				// make a command to run the shell script
				// NOTE: redirect to the log file here (and not in the host-processor),
				//       so the log is written as the correct user
				var cmd = Command(scriptPath.toString())
					.wrapShell { "$it > ${Posix.quote(outPath.toString())} 2>&1" }

				// emulate the SLURM environment variables
				// they're used in a ton of places in pyp
				cmd.envvars.add(EnvVar("SLURM_SUBMIT_DIR", "$clusterJob"))
				cmd.envvars.add(EnvVar("SLURM_JOB_ID", "${job.jobId}"))
				if (arrayId != null) {
					cmd.envvars.add(EnvVar("SLURM_ARRAY_JOB_ID", "${job.jobId}"))
					cmd.envvars.add(EnvVar("SLURM_ARRAY_TASK_ID", "$arrayId"))
				}
				if (numCpus != null) {
					cmd.envvars.add(EnvVar("SLURM_CPUS_PER_TASK", "$numCpus"))
				}
				if (memGiB != null) {
					cmd.envvars.add(EnvVar("SBATCH_MEM_PER_NODE", "${memGiB}G"))
				}
				if (numGpus != null) {
					cmd.envvars.add(EnvVar("SBATCH_GRES", "gpu:$numGpus"))

					// also restrict the Cuda devices
					// https://docs.nvidia.com/cuda/cuda-c-programming-guide/index.html#env-vars
					cmd.envvars.add(EnvVar("CUDA_VISIBLE_DEVICES", reservedGpus.joinToString(",")))
				}

				// add the job environment variables
				cmd.envvars.addAll(clusterJob.env)

				job.clusterJob.osUsername?.let { username ->
					cmd = Backend.instance.userProcessors.get(username).wrap(cmd)
				}

				process = Backend.instance.hostProcessor.exec(cmd)
			}
		}
	}

	private enum class WaitingReason(val msg: String) {
		Dependency("The job is waiting for another job to finish"),
		Resources("The job is waiting for more resources to become available")
	}

	private class Resource(val type: Type, val total: Int) {

		enum class Type {
			Cpu,
			Memory,
			Gpu
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
			Resource(Resource.Type.Cpu, config.availableCpus),
			Resource(Resource.Type.Memory, config.availableMemoryGiB),
			Resource(Resource.Type.Gpu, config.availableGpus)
		).associateBy { it.type }

		fun resource(type: Resource.Type): Resource =
			resources[type]
				?: throw NoSuchElementException("Resource type $type is not tracked")

		val gpus = ArrayList<Int>().apply {
			for (i in 0 until config.availableGpus) {
				add(i)
			}
		}


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

		fun removeAll(jobs: List<Job>) {
			for (job in jobs) {
				for (task in job.tasks) {
					_tasksWaiting.remove(task)
					_tasksRunning.remove(task)
					releaseResources(task)
				}
				_jobs.remove(job.jobId)
			}
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

				// find the next task that isn't waiting on anything or isn't canceled
				val task = _tasksWaiting
					.firstOrNull()
					?.takeIf { it.waitingReason(this) == null && !it.canceled && !it.job.canceled }
					?: break

				// yup, we got one

				// reserve resources for the task, if needed
				for ((requested, type) in task.job.requestedResources) {
					task.resourceReservations.add(resources.getValue(type).reserve(requested))
				}

				// reserve the individual GPUs, if needed
				val numGpus = task.job.numGpus
				if (numGpus != null) {
					for (i in 0 until numGpus) {
						val gpu = gpus.removeFirstOrNull()
							?: throw NoSuchElementException("Individual GPU not available, even though GPU resource says one should be")
						task.reservedGpus.add(gpu)
					}
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
			releaseResources(task)

			// cleanup the job, if needed
			if (task.job.tasks.all { it.finished }) {
				_jobs.remove(task.job.jobId)
				_finishedJobIds.add(task.job.jobId)
			}

			// keep going
			maybeStartTasks()
		}

		fun releaseResources(task: Job.Task) {

			for (reservation in task.resourceReservations) {
				resources[reservation.type]?.release(reservation)
			}
			task.resourceReservations.clear()

			// release any GPUs
			gpus.addAll(task.reservedGpus)
			task.reservedGpus.clear()
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

	override fun validate(job: ClusterJob) {

		val args = job.argsParsed

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

	override suspend fun launch(clusterJob: ClusterJob, scriptPath: Path): ClusterJob.LaunchResult {

		// create the job, and start it, if possible
		val job = jobs.use { jobs ->
			val job = Job(jobs.nextId(), clusterJob, scriptPath)
			jobs.add(job)
			jobs.maybeStartTasks()
			return@use job
		}

		return ClusterJob.LaunchResult(job.jobId, "", null)
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

	override suspend fun waitingReason(clusterJob: ClusterJob, launchResult: ClusterJob.LaunchResult): String? {
		jobs.use { jobs ->
			return launchResult.jobId?.let { jobs.waitingReason(it) }
		}
	}

	override suspend fun cancel(clusterJobs: List<ClusterJob>) {

		// lookup all the jobs before deleting anything
		val jobs = clusterJobs
			.mapNotNull { it.findJob() }

		// pass one: update all the canceled flags first,
		// so any concurrent signals won't try to start the canceled tasks before we get a chance to flag them
		for (job in jobs) {
			job.canceled = true
			job.tasks.forEach { it.canceled = true }
		}

		// pass two: remove the jobs and tasks from the lists
		this.jobs.use { it.removeAll(jobs) }

		// pass three: actually cancel any running tasks
		for (job in jobs) {
			for (task in job.tasks) {
				val process = task.process
				if (!task.finished && process != null) {

					Backend.log.debug("Asking job {} task {} to exit nicely ...", job.jobId, task.taskId)

					// ask the process to exit nicely
					// NOTE: we run all our jobs in a shell and shells usually respond to SIGINT instead of SIGTERM
					// NOTE: send SIGINT to the whole process group too, since shells won't forward SIGINT
					process.interrupt(processGroup=true)

					// but just in case, follow up with a less nice request later on
					Backend.instance.scope.launch {
						delay(10.seconds)

						// if the task is still running, take it down more forcefully
						if (process.status().isRunning) {
							Backend.log.warn("Job {} task {} didn't exit after asking nicely. Asking less nicely.", job.jobId, task.taskId)
							process.kill(processGroup=true)
						} else {
							Backend.log.debug("Job {} task {} exited after asking nicely.", job.jobId, task.taskId)
						}
					}

					// NOTE: the job end signal will be sent by the task process before it exits
					//       but since we already removed all the tasks and jobs, that signal should be ignored
				}
			}
		}

		// now that we've released resources, try to start more jobs
		this.jobs.use { it.maybeStartTasks() }
	}

	override suspend fun jobResult(clusterJob: ClusterJob, arrayIndex: Int?): ClusterJob.Result {

		// try to read the output file and delete it
		val outPath = clusterJob.outPath(arrayIndex)
		val out: String? = try {
			val out = outPath.readStringAs(clusterJob.osUsername)
			outPath.deleteAs(clusterJob.osUsername)
			out
		} catch (t: Throwable) {
			Backend.log.warn("Failed to retrieve cluster job output log file from: $outPath", t)
			null
		}

		// find the job and task, if possible
		val job = clusterJob.findJob()
			// job not found: either the server was restarted while a process was running,
			// or we canceled the job and decided to forget about it.
			// either way, just pretend the job was canceled
			?: return ClusterJob.Result(ClusterJobResultType.Canceled, out)
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

				availableGpus = ArrayList(jobs.gpus),

				jobs = jobs.jobs.values
					.toList()
					.sortedBy { it.created }
					.map { job ->
						StandaloneData.Job(
							standaloneId = job.jobId,
							clusterId = job.clusterJob.idOrThrow,
							ownerId = job.clusterJob.ownerId,
							name = job.clusterJob.clusterName,
							resources = job.requestedResources.map { (requested, type) ->
								type.toString() to requested
							},
							tasks = job.tasks.map { task ->
								StandaloneData.Task(
									taskId = task.taskId,
									arrayId = task.arrayId,
									resources = task.resourceReservations.associate { reservation ->
										reservation.type.name to reservation.num
									},
									reservedGpus = ArrayList(task.reservedGpus),
									pid = task.process?.pid?.toLong(),
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
