package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.Group
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import io.kvision.remote.RemoteOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass


/**
 * Arguments common to all session types
 */
interface SessionArgs {
	val name: String
	val groupId: String
	val values: ArgValuesToml
}

data class SessionPypNames(
	val group: String,
	val session: String
)

interface SessionDisplay {
	val groupName: String
}

sealed interface SessionData {

	val userId: String
	val sessionId: String
	val sessionNumber: Int?
	val created: Long
	val permissions: Set<SessionPermission>
	val path: String

	val newestArgs: SessionArgs?
	val newestDisplay: SessionDisplay?

	/** does this node have changes that haven't been run on the server yet? */
	fun isChanged(): Boolean

	// sadly, KVision doesn't expose serialization library config
	// so we have to add another seriaization layer to support polymorphism
	fun serialize(): String =
		json.encodeToString(this)

	val name: String get() =
		newestArgs?.name ?: "(unnamed session)"

	val numberedName: String get() =
		numberedName(sessionNumber, name)

	companion object {

		fun numberedName(number: Int?, name: String): String {
			return if (number != null) {
				"S${number}-$name"
			} else {
				name
			}
		}

		private val json = Json {
			serializersModule = SerializersModule {
				// NOTE: kotlinx.serialization still doesn't work automatically for sealed interfaces,
				// so we still have to explicitly list all the polymorphic subclasses
				polymorphic(SessionData::class) {
					subclass(SingleParticleSessionData::class)
					subclass(TomographySessionData::class)
				}
			}
		}

		fun deserialize(serialized: String): SessionData =
			json.decodeFromString(serialized)
	}
}

@Serializable
data class SingleParticleSessionArgs(
	override val name: String,
	override val groupId: String,
	override val values: ArgValuesToml
) : SessionArgs

@Serializable
data class SingleParticleSessionDisplay(
	override val groupName: String
) : SessionDisplay

@Serializable
data class SingleParticleSessionData(
	override val userId: String,
	override val sessionId: String,
	override val sessionNumber: Int?,
	override val created: Long,
	override val permissions: Set<SessionPermission>,
	override val path: String,
	val args: JobArgs<SingleParticleSessionArgs>,
	val display: JobArgs<SingleParticleSessionDisplay>,
	val numMicrographs: Long,
	val numFrames: Long
) : SessionData {
	override fun isChanged() = args.hasNext()

	override val newestArgs get() = args.newest()?.args
	override val newestDisplay get() = display.newest()?.args

	companion object {
		const val ID = "single-particle"
	}
}


@Serializable
data class TomographySessionArgs(
	override val name: String,
	override val groupId: String,
	override val values: ArgValuesToml
) : SessionArgs

@Serializable
data class TomographySessionDisplay(
	override val groupName: String
) : SessionDisplay

@Serializable
data class TomographySessionData(
	override val userId: String,
	override val sessionId: String,
	override val sessionNumber: Int?,
	override val created: Long,
	override val permissions: Set<SessionPermission>,
	override val path: String,
	val args: JobArgs<TomographySessionArgs>,
	val display: JobArgs<TomographySessionDisplay>,
	val numTiltSeries: Long,
	val numTilts: Long
) : SessionData {
	override fun isChanged() = args.hasNext()

	override val newestArgs get() = args.newest()?.args
	override val newestDisplay get() = display.newest()?.args

	companion object {
		const val ID = "tomography"
	}
}

@Serializable
enum class SessionDaemon(
	val label: String,
	/** ie, the `clusterName` of the `ClusterJob`, a value which is chosen by pyp */
	val clusterJobClusterName: String,
	val filename: String,
	val isSubDaemon: Boolean
) {

	Streampyp("File transfer", "pyp_sess_dta", "streampyp", false),
	Pypd("Data pre-processing", "pyp_sess_pre", "pypd", true),
	Fypd("2D/3D refinement", "pyp_sess_ref", "fypd", true);

	companion object {

		fun getByClusterJobClusterName(clusterJobClusterName: String?): SessionDaemon? =
			values().firstOrNull { it.clusterJobClusterName == clusterJobClusterName }

		fun mainDaemon(): SessionDaemon =
			values().first { it.isMainDaemon }

		fun subDaemons(): List<SessionDaemon> =
			values().filter { it.isSubDaemon }
	}

	val isMainDaemon: Boolean get() = !isSubDaemon
}

/**
 * because polymorphic serialization in KVision is too much of a pain
 */
@Serializable
data class RunningSessions(
	val singleParticle: List<SingleParticleSessionData>,
	val tomography: List<TomographySessionData>
)

@Serializable
data class SessionLogs(
	val logs: List<SessionLogData>
)

@Serializable
data class SessionLogData(
	val jobId: String,
	val daemon: SessionDaemon,
	val timestamp: Long
)

@Serializable
data class SessionCommands(
	val command: String
)

@Serializable
data class SessionLog(
	val log: String?
)

@Serializable
data class SessionSpeeds(
	/** for file transfers, in Gbps */
	val transfers: List<Double>
)

@Serializable
data class SessionJobsLogs(
	val jobs: List<SessionJobLogData>
)

@Serializable
data class SessionJobLogData(
	val jobId: String,
	val name: String,
	val timestamp: Long
)

@Serializable
data class SessionJobLogs(
	val representativeCommand: String,
	val logs: List<SessionJobLog>
)

@Serializable
data class SessionJobLog(
	val arrayId: Int?,
	val log: String?
)

@Serializable
data class CopySessionArgs(
	val name: String
)

@Serializable
enum class SessionPermission {
	Read,
	Write
}


/**
 * WARNING: be very careful changing these classes!
 * They get saved in the sessionExports database in serialized form,
 * so deserializing old data could cause problems depending on how backwards compatible kotlinx.serialization is.
 * Making changes here may require database migrations.
 */
sealed interface SessionExportRequest {

	fun serialize(): String =
		json.encodeToString(this)

	companion object {

		private val json = Json {
			// NOTE: kotlinx.serialization still doesn't work automatically for sealed interfaces,
			// so we still have to explicitly list all the polymorphic subclasses
			serializersModule = SerializersModule {
				polymorphic(SessionExportRequest::class) {
					subclass(Filter::class)
				}
			}
		}

		fun deserialize(encoded: String): SessionExportRequest =
			json.decodeFromString(encoded)
	}

	@Serializable
	data class Filter(
		val name: String
		// TODO: do we need to add args here? eg:
		//var args: Args? = null
	) : SessionExportRequest
}


@Serializable
data class SessionExportData(
	val sessionId: String,
	val exportId: String,
	/** serialized SessionExportRequest */
	val request: String,
	val created: Long,
	val clusterJobId: String?,
	/** serialized SessionExportResult */
	val result: String? = null
) {
	fun getRequest(): SessionExportRequest =
		SessionExportRequest.deserialize(request)

	fun getResult(): SessionExportResult? =
		result?.let { SessionExportResult.deserialize(it) }
}

/**
 * WARNING: be very careful changing these classes!
 * They get saved in the sessionExports database in serialized form,
 * so deserializing old data could cause problems depending on how backwards compatible kotlinx.serialization is.
 * Making changes here may require database migrations.
 */
sealed interface SessionExportResult {

	fun serialize(): String =
		json.encodeToString(this)

	companion object {

		private val json = Json {
			// NOTE: kotlinx.serialization still doesn't work automatically for sealed interfaces,
			// so we still have to explicitly list all the polymorphic subclasses
			serializersModule = SerializersModule {

				polymorphic(SessionExportResult::class) {
					subclass(Canceled::class)
					subclass(Failed::class)
					subclass(Succeeded::class)
				}

				polymorphic(Succeeded.Output::class) {
					subclass(Succeeded.Output.Filter::class)
				}
			}
		}

		fun deserialize(encoded: String): SessionExportResult =
			json.decodeFromString(encoded)
	}

	@Serializable
	class Canceled : SessionExportResult

	@Serializable
	class Failed(
		/** This message is displayed to the user, so make it readable */
		val reason: String
	) : SessionExportResult

	@Serializable
	class Succeeded(
		val output: Output
	) : SessionExportResult {

		sealed interface Output {

			@Serializable
			data class Filter(
				/** relative to the session folder */
				val path: String
			) : Output
		}
	}
}

@Serializable
enum class SessionExportStatus(val id: String) {
	Preparing("preparing"),
	Launched("launched"),
	Canceled("canceled"),
	Finished("finished")
}


@KVService
interface ISessionsService {

	@KVBindingRoute("sessions/canStart")
	suspend fun canStart(): Boolean

	@KVBindingRoute("sessions/sessionOptions")
	suspend fun sessionOptions(search: String?, initial: String?, state: String?): List<RemoteOption>

	@KVBindingRoute("sessions/groups")
	suspend fun groups(): List<Group>

	@KVBindingRoute("sessions/groupOptions")
	suspend fun groupOptions(search: String?, initial: String?, state: String?): List<RemoteOption>

	@KVBindingRoute("sessions/running")
	suspend fun running(): RunningSessions

	@KVBindingRoute("sessions/isRunning")
	suspend fun isRunning(sessionId: String, daemon: SessionDaemon): Boolean

	@KVBindingRoute("sessions/start")
	suspend fun start(sessionId: String, daemon: SessionDaemon)

	@KVBindingRoute("sessions/restart")
	suspend fun restart(sessionId: String, daemon: SessionDaemon)

	@KVBindingRoute("sessions/clear")
	suspend fun clear(sessionId: String, daemon: SessionDaemon)

	@KVBindingRoute("sessions/stop")
	suspend fun stop(sessionId: String, daemon: SessionDaemon)

	@KVBindingRoute("sessions/cancel")
	suspend fun cancel(sessionId: String)

	@KVBindingRoute("sessions/logs")
	suspend fun logs(sessionId: String, daemon: SessionDaemon): SessionLogs

	@KVBindingRoute("sessions/cmds")
	suspend fun commands(sessionId: String, jobId: String): SessionCommands

	@KVBindingRoute("sessions/log")
	suspend fun log(sessionId: String, jobId: String): SessionLog

	@KVBindingRoute("sessions/speeds")
	suspend fun speeds(sessionId: String): SessionSpeeds

	@KVBindingRoute("sessions/jobsLogs")
	suspend fun jobsLogs(sessionId: String): SessionJobsLogs

	@KVBindingRoute("sessions/jobLogs")
	suspend fun jobLogs(sessionId: String, jobId: String): SessionJobLogs

	@KVBindingRoute("sessions/avgRot")
	suspend fun getAvgRot(sessionId: String, dataId: String): Option<AvgRotData>

	@KVBindingRoute("sessions/motion")
	suspend fun getMotion(sessionId: String, dataId: String): Option<MotionData>

	@KVBindingRoute("sessions/dataLog")
	suspend fun dataLog(sessionId: String, dataId: String): String

	@KVBindingRoute("sessions/listFilters")
	suspend fun listFilters(sessionId: String): List<String>

	@KVBindingRoute("sessions/getFilter")
	suspend fun getFilter(sessionId: String, name: String): PreprocessingFilter

	@KVBindingRoute("sessions/saveFilter")
	suspend fun saveFilter(sessionId: String, filter: PreprocessingFilter)

	@KVBindingRoute("sessions/deleteFilter")
	suspend fun deleteFilter(sessionId: String, name: String)

	@KVBindingRoute("sessions/filterOptions")
	suspend fun filterOptions(search: String?, initial: String?, state: String?): List<RemoteOption>

	@KVBindingRoute("sessions/export")
	suspend fun export(sessionId: String, request: String /* serialized SessionExportRequest */, slurmValues: ArgValuesToml)

	@KVBindingRoute("sessions/cancelExport")
	suspend fun cancelExport(exportId: String)
}


@KVService
interface ISingleParticleSessionService {

	@KVBindingRoute("session/${SingleParticleSessionData.ID}/list")
	suspend fun list(): List<SingleParticleSessionData>

	@KVBindingRoute("session/${SingleParticleSessionData.ID}/create")
	suspend fun create(args: SingleParticleSessionArgs): SingleParticleSessionData

	@KVBindingRoute("session/${SingleParticleSessionData.ID}/edit")
	suspend fun edit(sessionId: String, args: SingleParticleSessionArgs?): SingleParticleSessionData

	@KVBindingRoute("session/${SingleParticleSessionData.ID}/get")
	suspend fun get(sessionId: String): SingleParticleSessionData

	@KVBindingRoute("session/${SingleParticleSessionData.ID}/delete")
	suspend fun delete(sessionId: String)

	@KVBindingRoute("session/${SingleParticleSessionData.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */

	@KVBindingRoute("session/${SingleParticleSessionData.ID}/copy")
	suspend fun copy(sessionId: String, args: CopySessionArgs): SingleParticleSessionData
}


@KVService
interface ITomographySessionService {

	@KVBindingRoute("session/${TomographySessionData.ID}/list")
	suspend fun list(): List<TomographySessionData>

	@KVBindingRoute("session/${TomographySessionData.ID}/create")
	suspend fun create(args: TomographySessionArgs): TomographySessionData

	@KVBindingRoute("session/${TomographySessionData.ID}/edit")
	suspend fun edit(sessionId: String, args: TomographySessionArgs?): TomographySessionData

	@KVBindingRoute("session/${TomographySessionData.ID}/get")
	suspend fun get(sessionId: String): TomographySessionData

	@KVBindingRoute("session/${TomographySessionData.ID}/delete")
	suspend fun delete(sessionId: String)

	@KVBindingRoute("session/${TomographySessionData.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */

	@KVBindingRoute("session/${TomographySessionData.ID}/copy")
	suspend fun copy(sessionId: String, args: CopySessionArgs): TomographySessionData

	@KVBindingRoute("session/${TomographySessionData.ID}/driftMetadata")
	suspend fun getDriftMetadata(sessionId: String, tiltSeriesId: String): Option<DriftMetadata>
}
