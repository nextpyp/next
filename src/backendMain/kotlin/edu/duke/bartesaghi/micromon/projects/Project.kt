package edu.duke.bartesaghi.micromon.projects

import com.mongodb.client.model.Updates.*
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.dir
import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.jobs.JobOwner
import edu.duke.bartesaghi.micromon.jobs.ProjectRun
import edu.duke.bartesaghi.micromon.LinkTree
import edu.duke.bartesaghi.micromon.linux.userprocessor.createDirsIfNeededAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.deleteDirRecursivelyAsyncAs
import edu.duke.bartesaghi.micromon.mongo.*
import edu.duke.bartesaghi.micromon.services.ProjectData
import edu.duke.bartesaghi.micromon.services.UserData
import org.bson.Document
import java.nio.file.Path
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.*
import kotlin.io.path.div


class Project(
	val userId: String,
	val projectId: String,
	val projectNumber: Int?,
	var name: String,
	val created: Instant,
	val readerUserIds: Set<String> = emptySet()
) {

	constructor (doc: Document) : this(
		userId = doc.getString("userId"),
		projectId = doc.getString("projectId"),
		projectNumber = doc.getInteger("projectNumber"),
		name = doc.getString("name"),
		created = Instant.ofEpochMilli(doc.getLong("created")),
		readerUserIds = doc.getKeySet("readerUserIds")
			.map { it.fromMongoSafeKey() }
			.toSet()
	)

	companion object {

		fun makeId(name: String) =
			name.toSafeFileName()

		operator fun get(userId: String, projectId: String): Project? =
			Database.projects.get(userId, projectId)?.let { Project(it) }

		fun getOrThrow(userId: String, projectId: String): Project =
			get(userId, projectId)
				?: throw NoSuchElementException("project not found $userId/$projectId")

		fun getOrThrow(job: Job): Project =
			getOrThrow(job.userId, job.projectId)

		fun dir(userId: String): Path =
			User.dir(userId) / "projects"

		fun dir(userId: String, projectId: String): Path =
			dir(userId) / projectId


		class RepresentativeImages {

			operator fun get(userId: String, projectId: String): List<String> {

				// Not really a job id, but a signal that an old-style representative image
				// has been shoehorned into the new system
				val legacyJobId = "legacy-job-id"

				// migrate from the old `images` format to the new `repImages` format if needed
				val oldUrls = Database.projects.get(userId, projectId)
					?.getMap<String>("images")
				if (oldUrls != null) {

					// convert each old url into a new representative image
					for (t in RepresentativeImageType.values()) {
						val oldUrl = oldUrls[t.id]
							?: continue
						this[userId, projectId, t, legacyJobId] = RepresentativeImage(
							Document().apply {
								set("url", oldUrl)
							}
						)
					}

					// remove the old images
					Database.projects.update(userId, projectId,
						unset("images")
					)
				}

				// look up the current representative image for this type
				val repImagesByTypeId = Database.projects.get(userId, projectId)
					?.getMap<Document>("repImages")
					?: return emptyList()

				return RepresentativeImageType.values()
					.mapNotNull map@{ type ->

						// get the newest representative image for this type
						val (jobId, repImage) = repImagesByTypeId[type.id]
							?.let { it.keys.map { jobId -> jobId to RepresentativeImage.fromDoc(it.getDocument(jobId)) } }
							?.maxByOrNull { (_, repImage) -> repImage.timestamp }
							?: return@map null

						if (jobId == legacyJobId) {
							// in the old system, we only had URLs
							repImage.params?.getString("url")
						} else {
							// get the image URL from the job
							Job.fromId(jobId)
								?.representativeImageUrl(repImage)
						}
					}
			}

			operator fun set(userId: String, projectId: String, type: RepresentativeImageType, jobId: String, repImage: RepresentativeImage?) {
				Database.projects.update(userId, projectId,
					if (repImage != null) {
						set("repImages.${type.id}.$jobId", repImage.toDoc())
					} else {
						unset("repImages.${type.id}.$jobId")
					}
				)
			}
		}
		val representativeImages = RepresentativeImages()

		fun setReader(userId: String, projectId: String, readerUserId: String) {
			Database.projects.update(userId, projectId,
				set("readerUserIds.${readerUserId.toMongoSafeKey()}", true)
			)
			Database.projectReaders.set(readerUserId, userId, projectId)
		}

		fun unsetReader(userId: String, projectId: String, readerUserId: String) {
			Database.projects.update(userId, projectId,
				unset("readerUserIds.${readerUserId.toMongoSafeKey()}")
			)
			Database.projectReaders.unset(readerUserId, userId, projectId)
		}
	}

	val dir: Path get() =
		dir(userId, projectId)

	suspend fun create(user: User) {

		// create the database entry
		Database.projects.create(userId, projectId) {
			set("name", name)
			set("projectNumber", projectNumber)
			set("created", created.toEpochMilli())
		}

		// create the folder
		dir.createDirsIfNeededAs(user.osUsername)
		LinkTree.projectCreated(this)
	}

	fun delete(user: User) {

		// delete all related cluster jobs
		Cluster.deleteAll(getRuns().flatMap { run ->
			run.jobs.map { jobRun ->
				JobOwner(jobRun.jobId, run.idOrThrow).toString()
			}
		})

		// delete all the jobs
		Database.jobs.deleteAllInProject(userId, projectId)

		// delete the database entry
		Database.projects.delete(userId, projectId)

		// delete from any readers
		for (readerUserId in readerUserIds) {
			Database.projectReaders.unset(readerUserId, userId, projectId)
		}

		// delete the files and folders, asynchronously
		dir.deleteDirRecursivelyAsyncAs(user.osUsername)
		LinkTree.projectDeleted(this)
	}

	fun deleteJobRuns(jobId: String) {

		// filter the runs
		for (run in getRuns()) {

			// filter the job runs down to this job
			val jobRuns = run.jobs
				.filter { jobRun -> jobRun.jobId == jobId }
				.takeIf { it.isNotEmpty() }
				?: continue

			// if we matched all the job runs, remove the whole run from the database
			if (jobRuns.size >= run.jobs.size) {

				Database.projects.update(userId, projectId,
					set("running.${run.idOrThrow}", null)
				)

			} else {

				// otherwise, remove just the matched job runs
				Database.projects.update(userId, projectId,
					*jobRuns
						.map { jobRun ->
							pull("running.${run.idOrThrow}.jobs", Document().apply {
								this["jobId"] = jobRun.jobId
							})
						}
						.toTypedArray()
				)
			}

			// delete all related cluster jobs
			Cluster.deleteAll(jobRuns.map { jobRun ->
				JobOwner(jobRun.jobId, run.idOrThrow).toString()
			})
		}
	}

	fun toData(user: User?): ProjectData {

		val ownerData = Database.users.getUser(userId)
			?.toData()
			?: UserData(userId, "(Unknown)")

		return ProjectData(
			ownerData,
			user.permissions(this),
			projectId,
			projectNumber,
			name,
			created.toEpochMilli(),
			representativeImages[userId, projectId],
			dir.toString()
		)
	}

	fun getRuns(): List<ProjectRun> =
		Database.projects.getWithOnlyRunning(userId, projectId)
			?.getListOfNullables<Document>("running")
			?.withIndex()
			?.mapNotNull { (doci, doc) -> doc?.let { ProjectRun.fromDoc(doci, it) } }
			?: emptyList()

	/**
	 * "recent" runs are runs from the last two weeks
	 */
	private fun runTimeThreshold(): Instant =
		ZonedDateTime.now()
			.with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.getDefault()).firstDayOfWeek))
			.minusWeeks(1)
			.toInstant()

	/** returns older then newer runs */
	fun getRunsPartition(): Pair<List<ProjectRun>,List<ProjectRun>> {
		val threshold = runTimeThreshold()
		return getRuns()
			.partition { it.timestamp < threshold }
	}

	fun getRun(runId: Int): ProjectRun? =
		Database.projects.getWithOnlyRunning(userId, projectId)
			?.getListOfNullables<Document>("running")
			?.get(runId)
			?.let { doc -> ProjectRun.fromDoc(runId, doc) }

	fun pushRun(run: ProjectRun): Int {

		// try to make these database operations atomic,
		// so we can reliably get the id of the run we're creating
		// this *should* work as long as the web server is the only process pushing runs to the database
		synchronized(pushLock) {

			Database.projects.update(userId, projectId,
				push("running", run.toDoc())
			)

			// get the index of the run we just pushed
			return Database.projects.getWithOnlyRunning(userId, projectId)!!
				.getListOfNullables<Document>("running")!!
				.size - 1
		}
	}

	fun updateRun(run: ProjectRun) {
		Database.projects.update(userId, projectId,
			set("running.${run.idOrThrow}", run.toDoc())
		)
	}


	inner class DownstreamInfo {

		// there's no good way to query MongoDB directly for jobs that have inputs,
		// so just get all the jobs in the project and we'll implement the query in memory ourselves
		val projectJobsById: Map<String,Job> = Job.allFromProject(userId, projectId).associateBy { it.idOrThrow }

		// index the downlinks of all the project jobs, for quick lookups
		val downstreamJobsByUpstreamId: Map<String,List<Job>> = projectJobsById.mapValues { (upstreamJobId, _) ->
			projectJobsById.values.filter { it.inputs.hasJob(upstreamJobId) }
		}

		/**
		 * Returns all jobs who depend directly on the outputs of the given job
		 */
		fun jobs(job: Job): List<Job> =
			jobs(job.idOrThrow)

		fun jobs(jobId: String): List<Job> =
			downstreamJobsByUpstreamId[jobId] ?: emptyList()

		/**
		 * Returns all jobs who depend on outputs of the given jobs, either directly or indirectly.
		 * Jobs are returned in BFS order.
		 */
		fun jobsIterative(queryJobs: List<Job>): List<Job> {

			val queue = ArrayDeque<Job>()
			val queueLookup = HashSet<String>()

			val jobsFound = ArrayList<Job>()
			val jobsFoundLookup = HashSet<String>()

			// start with the input jobs
			queue.addAll(queryJobs)
			jobsFoundLookup.addAll(queryJobs.map { it.idOrThrow })

			// basically, do BFS to get all the downstream jobs
			// assuming job relationships form a graph in general
			while (queue.isNotEmpty()) {

				// get the next job
				val job = queue.pollFirst()
				queueLookup.remove(job.idOrThrow)

				// did we find anything new?
				if (job.idOrThrow !in jobsFoundLookup) {
					jobsFound.add(job)
					jobsFoundLookup.add(job.idOrThrow)
				}

				for (downstreamJob in jobs(job)) {
					if (downstreamJob.idOrThrow !in jobsFoundLookup && downstreamJob.idOrThrow !in queueLookup) {
						queue.add(downstreamJob)
						queueLookup.add(downstreamJob.idOrThrow)
					}
				}
			}

			return jobsFound
		}

		/**
		 * Performs topological sort on the given query jobs,
		 * ensuring that every job that depends on another job appears after it in the sorted order
		 */
		fun topoSort(queryJobs: List<Job>): List<Job> {

			val queryJobsLookup = queryJobs.associateBy { it.idOrThrow }

			// find the source jobs in the query set
			// that is, jobs with no inputs from the query set
			val sourceJobs = queryJobs.filter { job ->
				val inputJobIds = job.inputs.jobIds()
				inputJobIds.none { it in queryJobsLookup }
			}

			// return the source jobs, then all the downstream query jobs in BFS order
			val jobOrder = ArrayList(sourceJobs)
			jobOrder += jobsIterative(sourceJobs).filter { it.idOrThrow in queryJobsLookup }
			return jobOrder
		}
	}
	fun downstreamInfo() =
		DownstreamInfo()
}

private val pushLock = Any()


data class RepresentativeImage(
	/** job-specific parameters used to create the URL */
	val params: Document? = null,
	val timestamp: Instant = Instant.now()
) {

	companion object {

		fun fromDoc(doc: Document) = RepresentativeImage(
			timestamp = doc.getLong("timestamp")?.let { Instant.ofEpochMilli(it) } ?: Instant.EPOCH,
			params = doc.getDocument("params")
		)
	}

	fun toDoc() = Document().apply {
		set("timestamp", timestamp.toEpochMilli())
		set("params", params)
	}
}

enum class RepresentativeImageType(val id: String) {

	GainCorrected("gain-corrected"),
	Map("map");

	companion object {

		operator fun get(id: String) =
			values().find { it.id == id }
	}
}
