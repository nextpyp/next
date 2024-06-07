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
import edu.duke.bartesaghi.micromon.services.CommonJobData
import edu.duke.bartesaghi.micromon.services.JobData
import edu.duke.bartesaghi.micromon.services.ProjectPermission
import edu.duke.bartesaghi.micromon.services.Service
import io.ktor.application.*
import io.kvision.remote.ServiceException
import org.bson.Document
import org.bson.conversions.Bson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
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

	inner class Inputs {

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
	suspend fun create(user: User) {

		if (id != null) {
			throw IllegalStateException("job has already been created")
		}

		// create the job
		val (id, number) = Database.jobs.create(userId, projectId) {
			createDoc(this)
		}
		this.id = id
		this.jobNumber = number

		// set the name
		name = baseConfig.name
		Database.jobs.update(id, set("name", name))

		createFolder(user)
		if (baseConfig.hasFiles) {
			LinkTree.jobCreated(Project.getOrThrow(this), this)
		}
	}

	fun update() {
		Database.jobs.update(idOrThrow, ArrayList<Bson>().apply {
			updateDoc(this)
		})
	}

	fun delete(user: User) {

		val id = id ?: throw IllegalStateException("job has no id")

		// remove any associated data
		wipeData()

		// delete any job runs in the project
		Project[userId, projectId]
			?.deleteJobRuns(idOrThrow)

		// delete the job from the database
		Database.jobs.delete(id)
		Database.projects.update(userId, projectId,
			pull("jobIds", id)
		)

		// remove any representative images
		for (type in RepresentativeImageType.values()) {
			Project.representativeImages[userId, projectId, type, idOrThrow] = null
		}

		// NOTE: deleting many files from networked file systems (eg NFS)
		// can be really slow, so do the deletion asynchronously and return
		// from this function immediately
		deleteFilesAsync(user)
		if (baseConfig.hasFiles) {
			LinkTree.jobDeleted(Project.getOrThrow(this), this)
		}

		this.id = null
	}

	suspend fun createFolder(user: User) {
		if (baseConfig.hasFiles) {
			dir.createDirsIfNeededAs(user.osUsername)
			wwwDir.createIfNeeded(user.osUsername)
		}
	}

	suspend fun deleteFiles(user: User) {

		// delete any folders/files associated with the job, if any
		if (baseConfig.hasFiles) {
			dir.deleteDirRecursivelyAs(user.osUsername)
		}
	}

	fun deleteFilesAsync(user: User) {
		if (baseConfig.hasFiles) {
			dir.deleteDirRecursivelyAsyncAs(user.osUsername)
		}
	}

	/** delete any job-specific data from the database that is associated with this job */
	open fun wipeData() {
		// nothing to do by default
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
		Project.dir(userId, projectId) / "${baseConfig.id}-$idOrThrow"

	val wwwDir: WebCacheDir get() =
		WebCacheDir(dir / "www")

	/** runs this job on the server */
	abstract suspend fun launch(runningUser: User, runId: Int)

	protected fun Pyp.launch(user: User, runId: Int, argValues: ArgValues, webName: String, clusterName: String) {
		launch(
			userId = user.id,
			webName = webName,
			clusterName = clusterName,
			owner = JobOwner(idOrThrow, runId).toString(),
			ownerListener = JobRunner,
			dir = dir,
			args = argValues.toPypCLI(),
			launchArgs = argValues.toSbatchArgs()
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
		Database.parameters.getParams(idOrThrow)

	fun pypParametersOrThrow(): ArgValues =
		pypParameters()
			?: throw ServiceException("no pyp parameters available")

	fun pypParametersOrNewestArgs(): ArgValues =
		pypParameters()
			?: newestArgValues()?.toArgValues(Backend.pypArgs)
			?: ArgValues(Backend.pypArgs)

	abstract fun newestArgValues(): ArgValuesToml?
	abstract fun finishedArgValues(): ArgValuesToml?

	fun finishedArgValuesOrThrow(): ArgValuesToml =
		finishedArgValues()
			?: throw NoSuchElementException("no finished arg values available")

	fun imagesScale(pypValues: ArgValues): ImagesScale =
		ImagesScale(
			pixelA = pypValues.scopePixel
				?: ImagesScale.default().pixelA,
			particleRadiusA = pypValues.detectRad
				.takeIf { it != 0.0 }
				?: ImagesScale.default().particleRadiusA
		)

	companion object {

		fun fromId(jobId: String): Job? {
			val doc = Database.jobs.get(jobId) ?: return null
			return fromDoc(doc)
		}

		fun fromIdOrThrow(jobId: String): Job =
			fromId(jobId) ?: throw NoSuchElementException("no job with id=$jobId")

		fun allFromProject(userId: String, projectId: String): List<Job> =
			Database.jobs.getAllInProject(userId, projectId) { docs ->
				docs
					.map { fromDoc(it) }
					.toList()
			}

		fun fromDoc(doc: Document): Job {
			val configId = doc.getString("configId") ?: throw NoSuchElementException("configId not set for job")
			val config = NodeConfigs[configId] ?: throw NoSuchElementException("no NodeConfig for id = $configId")
			return config.jobInfo.fromDoc(doc)
		}

		fun savePosition(jobId: String, x: Double, y: Double) {
			Database.jobs.update(jobId,
				set("x", x),
				set("y", y)
			)
		}

		fun saveName(jobId: String, name: String) {
			Database.jobs.update(jobId,
				set("name", name)
			)
		}
	}
}

inline fun <reified T: Job> CommonJobData.DataId.resolveJob(): T =
	Job.fromIdOrThrow(jobId) as T



data class AuthInfo<T:Job>(
	val user: User,
	val job: T
)

inline fun <reified T:Job> Service.authJob(permission: ProjectPermission, jobId: String): AuthInfo<T> =
	call.authJob(permission, jobId)

inline fun <reified T:Job> ApplicationCall.authJob(permission: ProjectPermission, jobId: String): AuthInfo<T> {
	val user = authOrThrow()
	val job = user.authJobOrThrow(permission, jobId).cast<T>()
	return AuthInfo(user, job)
}

inline fun <reified T:Job> Job.cast(): T =
	this as? T
		?: throw WrongJobTypeException(this, T::class)

class WrongJobTypeException(val job: Job, val expectedType: KClass<*>)
	: IllegalArgumentException("expected job ${job.id} to be a $expectedType, not a ${job::class}")
