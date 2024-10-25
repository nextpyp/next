package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.pyp.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.reflect.KProperty


/**
 * URL paths for the real-time services
 *
 * NOTE: these services no longer use KVision's websocket framework due to extreme performance issues with serialization
 */
object RealTimeServices {

	class RealTimeService {

		operator fun getValue(receiver: Any, property: KProperty<*>): String =
			"/ws/${property.name}"
	}

	@ExportRealtimeService("Project",
		AppPermission.ProjectListen,
		messagesC2S = [RealTimeC2S.ListenToProject::class],
		messagesS2C = [
			RealTimeS2C.ProjectStatus::class,
			RealTimeS2C.ProjectRunInit::class,
			RealTimeS2C.ProjectRunStart::class,
			RealTimeS2C.JobStart::class,
			RealTimeS2C.JobUpdate::class,
			RealTimeS2C.JobFinish::class,
			RealTimeS2C.ProjectRunFinish::class,
			RealTimeS2C.ClusterJobSubmit::class,
			RealTimeS2C.ClusterJobStart::class,
			RealTimeS2C.ClusterJobArrayStart::class,
			RealTimeS2C.ClusterJobArrayEnd::class,
			RealTimeS2C.ClusterJobEnd::class
		]
	)
	val project by RealTimeService()

	val singleParticlePreprocessing by RealTimeService()
	val singleParticlePurePreprocessing by RealTimeService()
	val singleParticleDenoising by RealTimeService()
	val singleParticlePicking by RealTimeService()
	val tomographyPreprocessing by RealTimeService()
	val tomographyPurePreprocessing by RealTimeService()
	val tomographyDenoising by RealTimeService()
	val tomographyDenoisingEval by RealTimeService()
	val tomographyPicking by RealTimeService()
	val tomographySegmentationOpen by RealTimeService()
	val tomographySegmentationClosed by RealTimeService()
	val tomographyPickingOpen by RealTimeService()
	val tomographyPickingClosed by RealTimeService()
	val tomographyParticlesEval by RealTimeService()
	val tomographySessionData by RealTimeService()
	val reconstruction by RealTimeService()
	val streamLog by RealTimeService()

	@ExportRealtimeService("SingleParticleSession",
		AppPermission.SessionListen,
		messagesC2S = [
			RealTimeC2S.ListenToSession::class,
			RealTimeC2S.SessionSettingsSaved::class
		],
		messagesS2C = [
			RealTimeS2C.SessionStatus::class,
			RealTimeS2C.SessionSmallData::class,
			RealTimeS2C.SessionLargeData::class,
			RealTimeS2C.UpdatedParameters::class,
			RealTimeS2C.SessionMicrograph::class,
			RealTimeS2C.SessionTwoDClasses::class,
			RealTimeS2C.SessionExport::class,
			RealTimeS2C.SessionFilesystems::class,
			RealTimeS2C.SessionTransferInit::class,
			RealTimeS2C.SessionTransferWaiting::class,
			RealTimeS2C.SessionTransferStarted::class,
			RealTimeS2C.SessionTransferProgress::class,
			RealTimeS2C.SessionTransferFinished::class,
			RealTimeS2C.SessionDaemonSubmitted::class,
			RealTimeS2C.SessionDaemonStarted::class,
			RealTimeS2C.SessionDaemonFinished::class,
			RealTimeS2C.SessionJobSubmitted::class,
			RealTimeS2C.SessionJobStarted::class,
			RealTimeS2C.SessionJobFinished::class
		]
	)
	val singleParticleSession by RealTimeService()

	@ExportRealtimeService("TomographySession",
		AppPermission.SessionListen,
		messagesC2S = [
			RealTimeC2S.ListenToSession::class,
			RealTimeC2S.SessionSettingsSaved::class
		],
		messagesS2C = [
			RealTimeS2C.SessionStatus::class,
			RealTimeS2C.SessionSmallData::class,
			RealTimeS2C.SessionLargeData::class,
			RealTimeS2C.UpdatedParameters::class,
			RealTimeS2C.SessionTiltSeries::class,
			RealTimeS2C.SessionExport::class,
			RealTimeS2C.SessionFilesystems::class,
			RealTimeS2C.SessionTransferInit::class,
			RealTimeS2C.SessionTransferWaiting::class,
			RealTimeS2C.SessionTransferStarted::class,
			RealTimeS2C.SessionTransferProgress::class,
			RealTimeS2C.SessionTransferFinished::class,
			RealTimeS2C.SessionDaemonSubmitted::class,
			RealTimeS2C.SessionDaemonStarted::class,
			RealTimeS2C.SessionDaemonFinished::class,
			RealTimeS2C.SessionJobSubmitted::class,
			RealTimeS2C.SessionJobStarted::class,
			RealTimeS2C.SessionJobFinished::class
		]
	)
	val tomographySession by RealTimeService()
}


// NOTE: these sealed classes require kotlinx-serialization v0.14+
//  which is available in KVision v2.4+

/**
 * client-to-server (C2S) messages for real-time communication
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
	data class ListenToTiltSerieses(
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
 * server-to-client (S2C) messages for real-time communication
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

	@Serializable
	data class Error(
		val name: String?,
		val msg: String?
	) : RealTimeS2C()


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
		val status: RunStatus,
		val errorMessage: String?
	) : RealTimeS2C() {

		constructor(runId: Int, job: JobData, status: RunStatus, errorMessage: String?): this(runId, job.jobId, job.serialize(), status, errorMessage)
		fun job() = JobData.deserialize(jobString)
	}

	@Serializable
	data class ProjectRunFinish(
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
		val pypStats: PypStats
	) : RealTimeS2C()

	@Serializable
	data class UpdatedMicrograph(
		val micrograph: MicrographMetadata
	) : RealTimeS2C()


	// tomography preprocessing messages

	@Serializable
	data class UpdatedTiltSeries(
		val tiltSeries: TiltSeriesData
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
		val tomoVirMethod: TomoVirMethod,
		val tomoVirRad: ValueA,
		val tomoVirBinn: Long,
		val tomoVirDetectMethod: TomoVirDetectMethod,
		val tomoSpkMethod: TomoSpkMethod,
		val tomoSpkRad: ValueA,
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
		val autoVirionsCount: Long = 0,
		val autoParticlesCount: Long = 0,
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
		@ExportServiceProperty(skip = true)
		val used: Double get() =
			bytesUsed.toDouble()/bytes.toDouble()

		@ExportServiceProperty(skip = true)
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
		val tiltSeries: TiltSeriesData
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
