package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.nodes.Workflow
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass


/**
 * Service for interacting with user projects
 */
@KVService
@ExportService("Projects")
interface IProjectsService {

	@ExportServiceFunction(AppPermission.ProjectList)
	@KVBindingRoute("projects/list")
	suspend fun list(userId: String): List<ProjectData>

	@KVBindingRoute("projects/create")
	suspend fun create(name: String): ProjectData

	@KVBindingRoute("projects/delete")
	suspend fun delete(userId: String, projectId: String)

	@KVBindingRoute("projects/get")
	suspend fun get(userId: String, projectId: String): ProjectData

	@KVBindingRoute("projects/newArgValues")
	suspend fun newArgValues(userId: String, projectId: String, inData: CommonJobData.DataId, nodeId: String): ArgValuesToml

	@KVBindingRoute("projects/run")
	suspend fun run(userId: String, projectId: String, jobIds: List<String>)

	@KVBindingRoute("projects/cancelRun")
	suspend fun cancelRun(userId: String, projectId: String, runId: Int)

	@KVBindingRoute("projects/listRunning")
	suspend fun listRunning(userId: String): List<ProjectData>

	@KVBindingRoute("projects/latestClusterJob")
	suspend fun latestRunId(jobId: String): Int

	@KVBindingRoute("projects/getJobs")
	suspend fun getJobs(userId: String, projectId: String): List<String /* JobData but serialized */>

	@KVBindingRoute("projects/positionJobs")
	suspend fun positionJobs(positions: List<JobPosition>)

	@KVBindingRoute("projects/deleteJobs")
	suspend fun deleteJobs(jobIds: List<String>)

	@KVBindingRoute("projects/clusterJobLog")
	suspend fun clusterJobLog(clusterJobId: String): ClusterJobLog

	@KVBindingRoute("projects/clusterJobArrayLog")
	suspend fun clusterJobArrayLog(clusterJobId: String, arrayId: Int): ClusterJobArrayLog

	@KVBindingRoute("projects/waitingReason")
	suspend fun waitingReason(clusterJobId: String): String

	@KVBindingRoute("projects/renameJob")
	suspend fun renameJob(jobId: String, name: String): String /* JobData but serialized */

	@KVBindingRoute("projects/wipeJob")
	suspend fun wipeJob(jobId: String, deleteFilesAndData: Boolean): String /* JobData but serialized */

	@KVBindingRoute("projects/olderRuns")
	suspend fun olderRuns(userId: String, projectId: String): List<ProjectRunData>

	@KVBindingRoute("projects/countSizes")
	suspend fun countSizes(userId: String, projectId: String): ProjectSizes

	@KVBindingRoute("projects/sharedUsers/get")
	suspend fun getSharedUsers(userId: String, projectId: String): List<UserData>

	@KVBindingRoute("projects/sharedUsers/set")
	suspend fun setSharedUser(userId: String, projectId: String, sharedUserId: String)

	@KVBindingRoute("projects/sharedUsers/unset")
	suspend fun unsetSharedUser(userId: String, projectId: String, sharedUserId: String)

	@KVBindingRoute("projects/workflows")
	suspend fun workflows(): List<Workflow>

	@KVBindingRoute("projects/workflow/args")
	suspend fun workflowArgs(workflowId: Int): String /* Args but serialized */
}


@Serializable
data class ProjectData(
	val owner: UserData,
	val permissions: Set<ProjectPermission>,
	val projectId: String,
	val projectNumber: Int?,
	val projectName: String,
	val created: Long,
	/** urls of representative images for the project, small size */
	val images: List<String>,
	val path: String
) {

	@ExportServiceProperty(skip=true)
	val numberedName: String get() =
		if (projectNumber != null) {
			"P${projectNumber}-$projectName"
		} else {
			projectName
		}
}

@Serializable
data class ProjectSizes(
	/**
	 * The number of bytes used by the project in files and folders in the filesystem
	 */
	val filesystemBytes: Long?
) {

	companion object {

		fun empty() =
			ProjectSizes(null)
	}
}


@Serializable
enum class ProjectPermission {
	Read,
	Write
}


/**
 * Argument storage for jobs.
 *
 * Jobs can have two sets of arguments:
 * * The `finished` arguments: the arguments that were used when running the job the last time.
 * * And/or the `next` arguments: the arguments that will be used to run the job the next time.
 */
@Serializable
class JobArgs<T> {

	/**
	 * job arguments that have been processed by a run
	 * ie, describes the current state of the job's output files
	 */
	var finished: T? = null

	/**
	 * job arguments pending for the next run
	 * ie, describes how the job's output files could be in the one possible future
	 */
	var next: T? = null

	// NOTE: KVision's serializer fails to handle properties without backing fields properly
	// so don't use them here, just use functions instead
	// the current docs for kotlinx.serialization say this should be handled correctly,
	// but maybe KVision isn't using the a version of kotlinx.serlialization with that behavior

	fun hasNext() = next != null

	fun finishedOrThrow() =
		finished ?: throw NoSuchElementException("job has no finished args")

	fun nextOrThrow() =
		next ?: throw NoSuchElementException("job has no next args")

	inner class ArgsInfo(val args: T, val isNext: Boolean)

	private fun T.info(isNext: Boolean) =
		ArgsInfo(this, isNext)

	inline fun <R> map(func: (T) -> R): JobArgs<R> {
		val mapped = JobArgs<R>()
		mapped.finished = finished?.let { func(it) }
		mapped.next = next?.let { func(it) }
		return mapped
	}

	fun newest(): ArgsInfo? =
		next?.info(true)
			?: finished?.info(false)

	fun newestOrThrow(): ArgsInfo =
		newest() ?: throw IllegalStateException("job has no args")

	enum class Type {
		Finished,
		Next,
		Newest
	}

	fun get(type: Type): ArgsInfo? =
		when (type) {
			Type.Finished -> finished?.info(false)
			Type.Next -> next?.info(true)
			Type.Newest -> newest()
		}

	fun getOrThrow(type: Type): ArgsInfo =
		when (type) {
			Type.Finished -> finishedOrThrow().info(false)
			Type.Next -> nextOrThrow().info(true)
			Type.Newest -> newestOrThrow()
		}

	enum class Diff(val shouldSave: Boolean) {

		NoChange(false),
		CreateNext(true),
		ChangeNext(true),
		DeleteNext(true);

		fun <T> newNextArgs(newArgs: T): T? =
			when (this) {
				CreateNext, ChangeNext -> newArgs
				DeleteNext -> null
				else -> throw IllegalStateException("diff $this is not saveable")
			}
	}

	/**
	 * Compares new args with finished and next args to determine changes, if any.
	 */
	fun diff(newArgs: T): Diff =
		if (finished != null) {
			if (next != null) {
				if (newArgs == finished) {
					Diff.DeleteNext
				} else if (newArgs == next) {
					Diff.NoChange
				} else {
					Diff.ChangeNext
				}
			} else {
				if (newArgs == finished) {
					Diff.NoChange
				} else {
					Diff.CreateNext
				}
			}
		} else {
			Diff.CreateNext
		}

	/**
	 * moves the next args to finished
	 */
	fun run() {
		if (next != null) {
			finished = next
			next = null
		}
	}

	/**
	 * move the finished args to next
	 */
	fun unrun() {
		if (finished != null && next == null) {
			next = finished
			finished = null
		}
	}

	companion object {

		fun <T> fromNext(args: T): JobArgs<T> =
			JobArgs<T>().apply {
				next = args
			}

		fun <A,B> Pair<JobArgs<A>,JobArgs<B>>.newest(): Pair<A,B>? {
			val (a, b) = this
			val an = a.newest()
			val bn = b.newest()
			return when {
				an == null && bn == null -> null
				an != null && bn != null -> {
					if (an.isNext == bn.isNext) {
						// got a match!
						an.args to bn.args
					} else {
						throw IllegalArgumentException("Pair of job args don't both match: AisNext=${an.isNext}, BisNext=${bn.isNext}")
					}
				}
				else -> throw IllegalArgumentException("Pair of job args don't both have arguments")
			}
		}
	}
}


@Serializable
data class CommonJobData(
	val name: String,
	val jobId: String,
	val jobNumber: Int?,
	val path: String,
	var x: Double,
	var y: Double,

	/** true iff the running the job now would change any of the outputs */
	var stale: Boolean,

	/** keyed by input data id */
	val inputs: MutableMap<String,DataId?> = HashMap()
) {

	/**
	 * A pointer to a job and one of its data.
	 */
	@Serializable
	data class DataId(
		val jobId: String,
		/** the id of the data in the given job, either input or output */
		val dataId: String
	)

	fun getInputOrThrow(data: NodeConfig.Data) =
		inputs[data.id] ?: throw NoSuchElementException("no input with id=${data.id} found for job $name")
}


interface JobData {

	val common: CommonJobData

	// forward some very common properties from the common
	val name: String get() = common.name
	val numberedName: String get() =
		common.jobNumber
			?.let { number -> "B$number-$name" }
			?: name
	val jobId: String get() = common.jobId


	/** does this node have changes that haven't been run on the server yet? */
	fun isChanged(): Boolean

	fun finishedArgValues(): ArgValuesToml?
	fun nextArgValues(): ArgValuesToml?

	fun newestArgValues(): ArgValuesToml? =
		nextArgValues() ?: finishedArgValues()

	// sadly, KVision doesn't expose serialization library config
	// so we have to add another seriaization layer to support polymorphism
	fun serialize(): String =
		json.encodeToString(this)

	companion object {

		private val json = Json {
			serializersModule = SerializersModule {
				// NOTE: could new support for sealed interfaces let us get rid of this?
				//       apparently not, doesn't work last time I tried on 2023-08-29
				polymorphic(JobData::class) {
					subclass(SingleParticleRawDataData::class)
					subclass(SingleParticleRelionDataData::class)
					subclass(SingleParticleImportDataData::class)
					subclass(SingleParticleSessionDataData::class)
					subclass(SingleParticlePreprocessingData::class)
					subclass(SingleParticlePurePreprocessingData::class)
					subclass(SingleParticleDenoisingData::class)
					subclass(SingleParticlePickingData::class)
					subclass(SingleParticleDrgnData::class)
					subclass(SingleParticleCoarseRefinementData::class)
					subclass(SingleParticleFineRefinementData::class)
					subclass(SingleParticleFlexibleRefinementData::class)
					subclass(SingleParticlePostprocessingData::class)
					subclass(SingleParticleMaskingData::class)
					subclass(TomographyRawDataData::class)
					subclass(TomographyRelionDataData::class)
					subclass(TomographyImportDataData::class)
					subclass(TomographyImportDataPureData::class)
					subclass(TomographySessionDataData::class)
					subclass(TomographyPreprocessingData::class)
					subclass(TomographyPurePreprocessingData::class)
					subclass(TomographyDenoisingTrainingData::class)
					subclass(TomographyDenoisingEvalData::class)
					subclass(TomographyPickingData::class)
					subclass(TomographyPickingOpenData::class)
					subclass(TomographyPickingClosedData::class)
					subclass(TomographySegmentationOpenData::class)
					subclass(TomographySegmentationClosedData::class)
					subclass(TomographyMiloTrainData::class)
					subclass(TomographyMiloEvalData::class)
					subclass(TomographyParticlesTrainData::class)
					subclass(TomographyParticlesEvalData::class)
					subclass(TomographyDrgnTrainData::class)
					subclass(TomographyDrgnEvalData::class)
					subclass(TomographyCoarseRefinementData::class)
					subclass(TomographyFineRefinementData::class)
					subclass(TomographyMovieCleaningData::class)
					subclass(TomographyFlexibleRefinementData::class)
				}
			}
		}

		fun deserialize(serialized: String): JobData =
			json.decodeFromString(serialized)
	}
}

@Serializable
data class JobPosition(
	val jobId: String,
	val x: Double,
	val y: Double
)

@Serializable
class ClusterJobLog(
	val representativeCommand: String,
	val commandParams: String?,
	val submitFailure: String?,
	val launchScript: String?,
	val launchResult: ClusterJobLaunchResultData?,
	val resultType: ClusterJobResultType?,
	val exitCode: Int?,
	val log: String?,
	val arraySize: Int?,
	val failedArrayIds: List<Int>
)

@Serializable
class ClusterJobLaunchResultData(
	val command: String?,
	val out: String,
	val success: Boolean
)

@Serializable
class ClusterJobArrayLog(
	val resultType: ClusterJobResultType?,
	val exitCode: Int?,
	val log: String?
)


@Serializable
data class UserData(
	val id: String,
	val name: String?
)


interface JobCopyArgs {
	val copyFromJobId: String
}
