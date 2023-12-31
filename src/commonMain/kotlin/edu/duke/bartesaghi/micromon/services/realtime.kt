package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.pyp.Block
import edu.duke.bartesaghi.micromon.pyp.ImagesScale
import edu.duke.bartesaghi.micromon.pyp.TomoVirMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


/**
 * URL paths for the real-time services
 *
 * NOTE: these services no longer use KVision's websocket framework due to extreme performance issues with serialization
 */
object RealTimeServices {

	private fun path(name: String): String =
		"/ws/$name"

	val project = path("project")
	val singleParticlePreprocessing = path("singleParticlePreprocessing")
	val tomographyPreprocessing = path("tomographyPreprocessing")
	val reconstruction = path("reconstruction")
	val postprocessing = path("postprocessing")
	val masking = path("masking")
	val streamLog = path("streamLog")
	val singleParticleSession = path("singleParticleSession")
	val tomographySession = path("tomographySession")
}


// NOTE: these sealed classes require kotlinx-serialization v0.14+
//  which is available in KVision v2.4+

/**
 * client -> server (C2S) messages for real-time communication
 */
@Serializable
sealed class RealTimeC2S {

	// sadly, KVision doesn't expose serialization library config
	// so we have to add another seriaization layer to support polymorphism
	fun toJson(): String =
		Json.encodeToString(this)

	companion object {
		fun fromJson(json: String): RealTimeC2S =
			Json.decodeFromString(json)
	}


	// overhead messages

	@Serializable
	class Ping : RealTimeC2S()


	// application messages

	@Serializable
	data class ListenToProject(
		val userId: String,
		val projectId: String
	) : RealTimeC2S()

	@Serializable
	data class ListenToSingleParticlePreprocessing(
		val jobId: String
	) : RealTimeC2S()

	@Serializable
	data class ListenToTomographyPreprocessing(
		val jobId: String
	) : RealTimeC2S()

	@Serializable
	data class ListenToReconstruction(
		val jobId: String
	) : RealTimeC2S()

	@Serializable
	data class ListenToJobStreamLog(
		val clusterJobId: String
	) : RealTimeC2S()

	@Serializable
	data class ListenToSession(
		val sessionId: String
	) : RealTimeC2S()

	@Serializable
	class SessionSettingsSaved : RealTimeC2S()

	@Serializable
	data class ListenToSessionStreamLog(
		val sessionId: String,
		val clusterJobId: String
	) : RealTimeC2S()
}

/**
 * server -> client (S2C) messages for real-time communication
 */
@Serializable
sealed class RealTimeS2C {

	// sadly, KVision doesn't expose serialization library config
	// so we have to add another seriaization layer to support polymorphism
	fun toJson(): String =
		Json.encodeToString(this)

	companion object {
		fun fromJson(json: String): RealTimeS2C =
			Json.decodeFromString(json)
	}


	// overhead messages

	@Serializable
	class Pong : RealTimeS2C()


	// project messages

	@Serializable
	data class ProjectStatus(
		val recentRuns: List<ProjectRunData>,
		val hasOlderRuns: Boolean,
		val blocks: List<Block>
	) : RealTimeS2C()

	@Serializable
	data class ProjectRunInit(
		val runId: Int,
		val timestamp: Long,
		val jobIds: List<String>
	) : RealTimeS2C()

	@Serializable
	data class ProjectRunStart(
		val runId: Int
	) : RealTimeS2C()

	@Serializable
	data class JobStart(
		val runId: Int,
		val jobId: String
	) : RealTimeS2C()

	@Serializable
	data class JobUpdate(
		val jobId: String,
		val jobString: String /*JobData, but serialized outside of KVision*/
	) : RealTimeS2C() {

		constructor(job: JobData): this(job.jobId, job.serialize())
		fun job() = JobData.deserialize(jobString)
	}

	@Serializable
	data class JobFinish(
		val runId: Int,
		val jobId: String,
		val jobString: String, /*JobData, but serialized outside of KVision*/
		val status: RunStatus
	) : RealTimeS2C() {

		constructor(runId: Int, job: JobData, status: RunStatus): this(runId, job.jobId, job.serialize(), status)
		fun job() = JobData.deserialize(jobString)
	}

	@Serializable
	class ProjectRunFinish(
		val runId: Int,
		val status: RunStatus
	) : RealTimeS2C()

	@Serializable
	data class ClusterJobSubmit(
		val runId: Int,
		val jobId: String,
		val clusterJobId: String,
		val clusterJobName: String?,
		val arraySize: Int?
	) : RealTimeS2C()

	@Serializable
	data class ClusterJobStart(
		val runId: Int,
		val jobId: String,
		val clusterJobId: String
	) : RealTimeS2C()

	@Serializable
	data class ClusterJobArrayStart(
		val runId: Int,
		val jobId: String,
		val clusterJobId: String,
		val arrayIndex: Int,
		val numStarted: Int
	) : RealTimeS2C()

	@Serializable
	data class ClusterJobArrayEnd(
		val runId: Int,
		val jobId: String,
		val clusterJobId: String,
		val arrayIndex: Int,
		val numEnded: Int,
		val numCanceled: Int,
		val numFailed: Int
	) : RealTimeS2C()

	@Serializable
	data class ClusterJobEnd(
		val runId: Int,
		val jobId: String,
		val clusterJobId: String,
		val resultType: ClusterJobResultType
	) : RealTimeS2C()


	// single particle preprocessing messages

	@Serializable
	data class UpdatedParameters(
		val pypStats: PypStats,
		val imagesScale: ImagesScale
	) : RealTimeS2C()

	@Serializable
	data class UpdatedMicrograph(
		val micrograph: MicrographMetadata
	) : RealTimeS2C()


	// tomography preprocessing messages

	@Serializable
	data class UpdatedTiltSeries(
		val tiltSeries: TiltSeriesData,
		val numAutoParticles: Long?,
		val numAutoVirions: Long?
	) : RealTimeS2C()


	// reconstruction messages

	@Serializable
	data class UpdatedReconstruction(
		val reconstruction: ReconstructionData
	) : RealTimeS2C()

	@Serializable
	data class UpdatedRefinement(
		val refinement: RefinementData
	) : RealTimeS2C()

	@Serializable
	data class UpdatedRefinementBundle(
		val refinementBundle: RefinementBundleData
	) : RealTimeS2C()


	// streaming log messages

	@Serializable
	data class StreamLogInit(
		val messages: List<StreamLogMsg>,
		val resultType: ClusterJobResultType?,
		val exitCode: Int?
	) : RealTimeS2C()

	@Serializable
	data class StreamLogMsgs(
		val messages: List<StreamLogMsg>
	) : RealTimeS2C()

	@Serializable
	data class StreamLogEnd(
		val resultType: ClusterJobResultType,
		val exitCode: Int?
	) : RealTimeS2C()


	// session messages

	@Serializable
	data class SessionStatus(
		/** in order of SessionDaemon.values() */
		val daemonsRunning: List<Boolean>,
		val jobsRunning: List<SessionRunningJob>,
		val imagesScale: ImagesScale?,
		val tomoVirMethod: TomoVirMethod,
		val tomoVirRad: Double,
		val tomoVirBinn: Long
	) : RealTimeS2C() {

		fun isRunning(daemon: SessionDaemon): Boolean
			= daemonsRunning[daemon.ordinal]
	}

	@Serializable
	data class SessionSmallData(
		val exports: List<SessionExportData> = emptyList()
	) : RealTimeS2C()

	@Serializable
	data class SessionLargeData(
		val micrographs: List<MicrographMetadata> = emptyList(),
		val tiltSerieses: List<TiltSeriesData> = emptyList(),
		val twoDClasses: List<TwoDClassesData> = emptyList(),
	) : RealTimeS2C()

	@Serializable
	data class SessionRunningJob(
		val jobId: String,
		val name: String,
		val size: Int,
		val status: RunStatus
	)

	@Serializable
	data class SessionDaemonSubmitted(
		val jobId: String,
		val daemon: SessionDaemon
	) : RealTimeS2C()

	@Serializable
	data class SessionDaemonStarted(
		val jobId: String,
		val daemon: SessionDaemon
	) : RealTimeS2C()

	@Serializable
	data class SessionDaemonFinished(
		val jobId: String,
		val daemon: SessionDaemon
	) : RealTimeS2C()

	@Serializable
	data class SessionJobSubmitted(
		val jobId: String,
		val name: String,
		val size: Int
	) : RealTimeS2C()

	@Serializable
	data class SessionJobStarted(
		val jobId: String
	) : RealTimeS2C()

	@Serializable
	data class SessionJobFinished(
		val jobId: String,
		val resultType: ClusterJobResultType
	) : RealTimeS2C()


	@Serializable
	data class SessionFilesystem(
		val path: String,
		val type: String,
		val bytes: Long,
		val bytesUsed: Long
	) {
		val used: Double get() =
			bytesUsed.toDouble()/bytes.toDouble()

		val bytesAvailable: Long get() =
			bytes - bytesUsed
	}

	@Serializable
	data class SessionFilesystems(
		val filesystems: List<SessionFilesystem>
	) : RealTimeS2C()

	@Serializable
	data class SessionTransferInit(
		val files: List<FileInfo>,
	) : RealTimeS2C() {

		@Serializable
		data class FileInfo(
			val filename: String,
			val bytesTotal: Long,
			val bytesTransfered: Long
		)
	}

	@Serializable
	data class SessionTransferWaiting(
		val filename: String,
	) : RealTimeS2C()

	@Serializable
	data class SessionTransferStarted(
		val filename: String,
		val bytesTotal: Long
	) : RealTimeS2C()

	@Serializable
	data class SessionTransferProgress(
		val filename: String,
		val bytesTransferred: Long
	) : RealTimeS2C()

	@Serializable
	data class SessionTransferFinished(
		val filename: String
	) : RealTimeS2C()

	@Serializable
	data class SessionMicrograph(
		val micrograph: MicrographMetadata
	) : RealTimeS2C()

	@Serializable
	data class SessionTiltSeries(
		val tiltSeries: TiltSeriesData,
		val numAutoParticles: Long?,
		val numAutoVirions: Long?
	) : RealTimeS2C()

	@Serializable
	data class SessionTwoDClasses(
		val twoDClasses: TwoDClassesData
	) : RealTimeS2C()

	@Serializable
	data class SessionExport(
		val export: SessionExportData
	) : RealTimeS2C()
}

@Serializable
data class ProjectRunData(
	val runId: Int,
	val timestamp: Long,
	val status: RunStatus,
	val jobs: List<JobRunData>
)

@Serializable
data class JobRunData(
	val jobId: String,
	val status: RunStatus,
	val clusterJobs: List<ClusterJobData>
)

@Serializable
data class ClusterJobData(
	val clusterJobId: String,
	val webName: String?,
	val clusterName: String?,
	val status: RunStatus,
	val arrayInfo: ArrayInfo?
) {

	@Serializable
	data class ArrayInfo(
		val size: Int,
		val started: Int,
		val ended: Int,
		val canceled: Int,
		val failed: Int
	)
}

@Serializable
enum class ClusterJobResultType(val id: String) {

	Success("success"),
	Failure("failure"),
	Canceled("canceled");

	companion object {

		operator fun get(id: String) =
			values().find { it.id == id }
				?: throw NoSuchElementException("unrecognized result id: $id")
	}
}

@Serializable
enum class RunStatus(val id: String, val oldIds: List<String>? = null) {

	Waiting("waiting"),
	Running("running"),
	Succeeded("succeeded", listOf("finished")),
	Failed("failed"),
	Canceled("canceled");

	companion object {

		operator fun get(id: String) =
			values().find { it.id == id || it.oldIds?.contains(id) == true }
				?: throw NoSuchElementException("no status with id=$id")
	}
}


@Serializable
class StreamLogMsg(
	val timestamp: Long,
	val level: Int,
	val path: String,
	val line: Int,
	val msg: String
)
