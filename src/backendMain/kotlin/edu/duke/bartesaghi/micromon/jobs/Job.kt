package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates.*
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.cluster.slurm.toSbatchArgs
import edu.duke.bartesaghi.micromon.linux.userprocessor.*
import edu.duke.bartesaghi.micromon.projects.Project
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.authJobOrThrow
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.nodes.NodeConfigs
import edu.duke.bartesaghi.micromon.projects.RepresentativeImage
import edu.duke.bartesaghi.micromon.projects.RepresentativeImageType
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.ktor.application.*
import io.kvision.remote.ServiceException
import org.bson.Document
import org.bson.conversions.Bson
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.*
import kotlin.io.path.div
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


abstract class Job(
	/** the user id of the project owner */
	val userId: String,
	val projectId: String,
	val baseConfig: NodeConfig
) {

	var id: String? = null
		protected set
	val idOrThrow get() =
		id ?: throw NoSuchElementException("job has no id")

	fun projectOrThrow(): Project =
		Project.getOrThrow(this)

	var jobNumber: Int? = null
		protected set

	var name: String = baseConfig.name

	// placement in the diagram GUI
	var x: Double = 0.0
	var y: Double = 0.0

	var stale: Boolean = true

	fun CommonJobData.DataId.toDoc() = Document().apply {
		this["jobId"] = jobId
		this["dataId"] = dataId
	}

	fun CommonJobData.DataId.Companion.fromDoc(doc: Document) = CommonJobData.DataId(
		doc.getString("jobId"),
		doc.getString("dataId")
	)

	inner class Inputs : Iterable<CommonJobData.DataId> {

		private val inputsByData = HashMap<String,CommonJobData.DataId>()

		operator fun get(data: NodeConfig.Data): CommonJobData.DataId? =
			inputsByData[data.id]

		operator fun set(data: NodeConfig.Data, input: CommonJobData.DataId?) {
			if (input != null) {
				inputsByData[data.id] = input
			} else {
				inputsByData.remove(data.id)
			}
		}

		fun toDoc() = Document().apply {
			for (data in baseConfig.inputs) {
				this[data.id] = inputsByData[data.id]?.toDoc()
			}
		}

		fun fromDoc(doc: Document) {
			inputsByData.clear()
			for (dataId in doc.keys) {
				val inputDoc = doc.getDocument(dataId) ?: continue
				inputsByData[dataId] = CommonJobData.DataId.fromDoc(inputDoc)
			}
		}

		fun jobIds(): List<String> =
			inputsByData.values.map { it.jobId }

		fun hasJob(jobId: String?): Boolean =
			inputsByData.values.any { it.jobId == jobId }

		override fun iterator(): Iterator<CommonJobData.DataId> =
			inputsByData.values.iterator()

		override fun toString() = inputsByData.values.joinToString(", ")
	}
	val inputs = Inputs()

	inner class InputProp(val data: NodeConfig.Data) {

		operator fun getValue(thisRef: Job?, property: KProperty<*>): CommonJobData.DataId? =
			inputs[data]

		operator fun setValue(thisRef: Job?, property: KProperty<*>, value: CommonJobData.DataId?) {
			inputs[data] = value
		}
	}

	protected open fun createDoc(doc: Document) {
		doc["configId"] = baseConfig.id
		doc["userId"] = userId
		doc["projectId"] = projectId
		doc["name"] = name
		doc["x"] = x
		doc["y"] = y
		doc["stale"] = stale
		doc["inputs"] = inputs.toDoc()
	}

	protected open fun updateDoc(updates: MutableList<Bson>) {
		updates.add(set("name", name))
		updates.add(set("x", x))
		updates.add(set("y", y))
		updates.add(set("stale", stale))
		updates.add(set("inputs", inputs.toDoc()))
	}

	protected open fun fromDoc(doc: Document) {
		id = doc.getObjectId("_id").toStringId()
		jobNumber = doc.getInteger("jobNumber")
		name = doc.getString("name") ?: baseConfig.name
		x = doc.getDouble("x") ?: 0.0
		y = doc.getDouble("y") ?: 0.0
		stale = doc.getBoolean("stale") ?: false
		inputs.fromDoc(doc.getDocument("inputs"))
	}

	/**
	 * Create a new job in the database.
	 * Throws an error if this job has already been created
	 */
	suspend fun create() {

		if (id != null) {
			throw IllegalStateException("job has already been created")
		}

		// create the job
		val (id, number) = Database.instance.jobs.create(userId, projectId) {
			createDoc(this)
		}
		this.id = id
		this.jobNumber = number

		// set the name
		name = baseConfig.name
		Database.instance.jobs.update(id, set("name", name))

		createFolder()
		if (baseConfig.hasFiles) {
			LinkTree.jobCreated(projectOrThrow(), this)
		}
	}

	fun update() {
		Database.instance.jobs.update(idOrThrow, ArrayList<Bson>().apply {
			updateDoc(this)
		})
	}

	suspend fun delete() {

		val id = id ?: throw IllegalStateException("job has no id")

		// remove any associated data
		wipeData()

		// delete any job runs in the project
		Project[userId, projectId]
			?.deleteJobRuns(idOrThrow)

		// delete the job from the database
		Database.instance.jobs.delete(id)
		Database.instance.projects.update(userId, projectId,
			pull("jobIds", id)
		)

		// remove any representative images
		for (type in RepresentativeImageType.values()) {
			Project.representativeImages[userId, projectId, type, idOrThrow] = null
		}

		// NOTE: deleting many files from networked file systems (eg NFS)
		// can be really slow, so do the deletion asynchronously and return
		// from this function immediately
		deleteFilesAsync()
		if (baseConfig.hasFiles) {
			LinkTree.jobDeleted(projectOrThrow(), this)
		}

		this.id = null
	}

	suspend fun createFolder() {
		if (baseConfig.hasFiles) {
			val project = projectOrThrow()
			dir.createDirsIfNeededAs(project.osUsername)
			wwwDir.createIfNeeded()
		}
	}

	suspend fun deleteFiles() {

		// delete any folders/files associated with the job, if any
		if (baseConfig.hasFiles) {
			dir.deleteDirRecursivelyAs(projectOrThrow().osUsername)
		}
	}

	fun deleteFilesAsync() {
		if (baseConfig.hasFiles) {
			dir.deleteDirRecursivelyAsyncAs(projectOrThrow().osUsername)
		}
	}

	/** delete any job-specific data from the database that is associated with this job */
	open fun wipeData() {
		// nothing to do by default
	}

	open fun copyDataFrom(otherJobId: String) {
		throw UnsupportedOperationException("not implemented")
	}

	suspend fun copyFilesFrom(osUsername: String?, otherJobId: String) {
		val otherJob = fromIdOrThrow(otherJobId)
		otherJob.dir.copyDirRecursivelyAs(osUsername, dir)
	}

	/** return true iff this job has pending changes that need to be run */
	open fun isChanged(): Boolean = false

	abstract suspend fun data(): JobData

	fun commonData(): CommonJobData {

		val out = CommonJobData(
			name,
			idOrThrow,
			jobNumber,
			dir.toString(),
			x, y,
			stale
		)

		for (inputData in baseConfig.inputs) {
			out.inputs[inputData.id] = inputs[inputData]
		}

		return out
	}

	val dir: Path get() =
		dir(projectOrThrow(), baseConfig, idOrThrow)

	val wwwDir: WebCacheDir get() {
		val project = projectOrThrow()
		return WebCacheDir(dir(project, baseConfig, idOrThrow) / "www", project.osUsername)
	}

	fun ancestry(includeThis: Boolean = true): List<Job> {

		val jobs = ArrayList<Job>()

		var job = this
		while (true) {

			if (job !== this || includeThis) {
				jobs.add(job)
			}

			// recurse to the parent job
			val inputs = job.inputs.toList()
			when (inputs.size) {
				0 -> break // source job: no parents
				1 -> job = inputs[0].resolveJob<Job>()
				else -> throw IllegalStateException("Can't get job ancestry: Job ${job.baseConfig.id}=${job.idOrThrow} has multiple parents")
			}
		}

		return jobs
	}

	fun launchArgValues(): ArgValues {

		val values = ArgValues(Backend.instance.pypArgsWithMicromon)
		val launchingArgs = baseConfig.jobInfo.args()

		val debugMsg =
			if (Backend.log.isDebugEnabled) {
				StringBuilder().apply {
					append("launchArgValues()  job=${baseConfig.id}")
				}
			} else {
				null
			}

		// iterate jobs in stream order, so downstream jobs overwrite upstream jobs
		for (job in ancestry().reversed()) {

			if (Backend.log.isDebugEnabled) {
				debugMsg?.append("""
					|
					|  ${job.baseConfig.id}=${job.id}
				""".trimMargin())
			}

			// combine values from the job
			val jobValues = (job.newestArgValues() ?: "")
				.toArgValues(job.baseConfig.jobInfo.args())
			for (arg in values.args.args) {

				// skip args shared with the launching job,
				// so we can't overwrite the launching job's values with anything from upstream
				if (job !== this && launchingArgs.hasArg(arg)) {
					continue
				}

				// skip args that aren't in the job
				if (!jobValues.args.hasArg(arg)) {
					continue
				}

				// copy the value, but remove any explicit defaults
				// NOTE: this allows default values to override upstream values by removing them
				val value = jobValues[arg]
					?.takeIf { arg.default == null || it != arg.default.value }

				if (Backend.log.isDebugEnabled) {
					if (values[arg] != value) {
						debugMsg?.append("""
							|
							|    ${arg.fullId} = $value
						""".trimMargin())
					}
				}

				values[arg] = value
			}
		}

		// explicitly set the block id
		values.micromonBlock = baseConfig.id

		// set the data parent, if needed
		inputs.firstOrNull()
			?.resolveJob<Job>()
			?.let { job -> values.dataParent = job.dir.toString() }

		if (Backend.log.isDebugEnabled) {
			debugMsg?.append("""
				|
				|  MERGED
				|    ${values.toString().lines().joinToString("\n    ")}
			""".trimMargin())
			Backend.log.debug(debugMsg?.toString())
		}

		return values
	}

	/** runs this job on the server */
	abstract suspend fun launch(runId: Int)

	protected suspend fun Pyp.launch(
		project: Project,
		runId: Int,
		argValues: ArgValues,
		webName: String,
		clusterName: String
	) {

		// write the parameters file
		val paramsPath = pypParamsPath()
		val argValuesToml = argValues.toToml()
		paramsPath.writeStringAs(project.osUsername, argValuesToml)

		// launch the cluster job
		launch(
			userId = project.userId,
			osUsername = project.osUsername,
			webName = webName,
			clusterName = clusterName,
			owner = JobOwner(idOrThrow, runId).toString(),
			ownerListener = JobRunner,
			dir = dir,
			args = listOf("-params_file=$paramsPath"),
			params = argValuesToml,
			launchArgs = argValues.toSbatchArgs(),
			template = argValues.slurmTemplate
		)
	}

	open suspend fun finished() {
		// nothing to do by default
	}

	/** cancels any jobs started by launch */
	open suspend fun cancel(runId: Int): Cluster.CancelResult =
		Pyp.cancel(JobOwner(idOrThrow, runId).toString())

	open fun representativeImage(): RepresentativeImage? =
		null

	open fun representativeImageUrl(repImage: RepresentativeImage): String? =
		null

	fun pypParameters(): ArgValues? =
		Database.instance.parameters.getParams(idOrThrow)

	fun pypParametersOrThrow(): ArgValues =
		pypParameters()
			?: throw ServiceException("no pyp parameters available")

	fun pypParametersOrNewestArgs(): ArgValues =
		pypParameters()
			?: newestArgValues()?.toArgValues(Backend.instance.pypArgsWithMicromon)
			?: ArgValues(Backend.instance.pypArgsWithMicromon)

	abstract fun newestArgValues(): ArgValuesToml?
	abstract fun finishedArgValues(): ArgValuesToml?

	fun finishedArgValuesOrThrow(): ArgValuesToml =
		finishedArgValues()
			?: throw NoSuchElementException("no finished arg values available")

	/**
	 * Returns a manually-picked particles list for this job, if one exists.
	 */
	fun manualParticlesList(chosenListName: String? = null): ParticlesList? {

		// get all the particle lists
		val lists = Database.instance.particleLists.getAll(idOrThrow)

		// if no manual lists exists, there's nothing to return
		val manualLists = lists
			.filter { it.source == ParticlesSource.User }
			.takeIf { it.isNotEmpty() }
			?: return null

		val listName = when (this) {

			// older jobs with combined preprocessing need a user-specified list name
			is CombinedManualParticlesJob -> {

				// if no list was chosen explicitly, use no manual particles
				// NOTE: This function does not determine whether a list name is required,
				//       since getting that right in all cases requires more context than is available here.
				//       Callers of this function should perform requirements checks if they're needed.
				chosenListName
					?: return null

				// if the name references an auto list, that's not a manual list, so return null
				lists
					.filter { it.source == ParticlesSource.Pyp }
					.find { it.name == chosenListName }
					?.let { return null }

				// otherwise, look for the chosen list name
				chosenListName
			}

			// newer jobs use constant list names. Easy peasy.
			is ManualParticlesJob -> manualParticlesListName()

			// job doesn't export any manual particles
			else -> return null
		}

		return manualLists
			.find { it.name == listName }
			?: throw ServiceException("No manual particles list found with name: $listName")
	}

	fun pypParamsPath(): Path =
		pypParamsPath(this)


	companion object {

		private val log = LoggerFactory.getLogger("Job")

		fun fromId(jobId: String): Job? {
			val doc = Database.instance.jobs.get(jobId) ?: return null
			return fromDoc(doc)
		}

		fun fromIdOrThrow(jobId: String): Job =
			fromId(jobId) ?: throw NoSuchElementException("no job with id=$jobId")

		fun allFromProject(userId: String, projectId: String): List<Job> =
			Database.instance.jobs.getAllInProject(userId, projectId) { docs ->
				docs
					// just ignore jobs we can't recognize (like jobs in the database whose code has been deleted)
					.filter { configFromDoc(it) != null }
					.map { fromDoc(it) }
					.toList()
			}

		private fun configFromDoc(doc: Document): NodeConfig? {
			val jobId = doc.getObjectId("_id").toStringId()
			val configId = doc.getString("configId")
				?: run {
					log.warn("Job=$jobId database entry has no configId")
					return null
				}
			val config = NodeConfigs[configId]
				?: run {
					log.warn("Job=$jobId configId=$configId has no NodeConfig")
					return null
				}
			return config
		}

		private fun configFromDocOrThrow(doc: Document): NodeConfig =
			configFromDoc(doc)
				?: throw NoSuchElementException("Unknown kind of job, see log for more detailed warnings")

		fun fromDoc(doc: Document): Job {
			return configFromDocOrThrow(doc).jobInfo.fromDoc(doc)
		}

		fun savePosition(jobId: String, x: Double, y: Double) {
			Database.instance.jobs.update(jobId,
				set("x", x),
				set("y", y)
			)
		}

		fun saveName(jobId: String, name: String) {
			Database.instance.jobs.update(jobId,
				set("name", name)
			)
		}

		fun dir(project: Project, config: NodeConfig, jobId: String): Path =
			project.dir / "${config.id}-$jobId"

		fun pypParamsPath(job: Job): Path =
			job.dir / "pyp_params.toml"
	}
}

inline fun <reified T: Job> CommonJobData.DataId.resolveJob(): T =
	Job.fromIdOrThrow(jobId) as T



data class AuthInfo<T:Job>(
	val user: User,
	val job: T
)

inline fun <reified T:Job> Service.authJob(jobId: String, permission: ProjectPermission): AuthInfo<T> =
	call.authJob(jobId, permission)

inline fun <reified T:Job> ApplicationCall.authJob(jobId: String, permission: ProjectPermission): AuthInfo<T> {
	val user = authOrThrow()
	val job = user.authJobOrThrow(permission, jobId).cast<T>()
	return AuthInfo(user, job)
}

inline fun <reified T:Job> Job.cast(): T =
	this as? T
		?: throw WrongJobTypeException(this, T::class)

class WrongJobTypeException(val job: Job, val expectedType: KClass<*>)
	: IllegalArgumentException("expected job ${job.id} to be a $expectedType, not a ${job::class}")
