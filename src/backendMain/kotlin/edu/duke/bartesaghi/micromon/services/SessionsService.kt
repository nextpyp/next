package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.sessions.*
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.kvision.remote.RemoteOption
import io.kvision.remote.ServiceException
import java.io.FileNotFoundException


actual class SessionsService : ISessionsService, Service {

	companion object {

		private fun getMicrographOrThrow(sessionId: String, micrographId: String): Micrograph =
			Micrograph.get(sessionId, micrographId)
				?: throw NoSuchElementException("no micrograph with id $micrographId found in session $sessionId")

		private fun getTiltSeriesOrThrow(sessionId: String, tiltSeriesId: String): TiltSeries =
			TiltSeries.get(sessionId, tiltSeriesId)
				?: throw NoSuchElementException("no tilt series with id $tiltSeriesId found in session $sessionId")

		fun init(routing: Routing) {

			routing.route("kv/sessions/{sessionId}") {

				route("data/{dataId}") {

					get("image/{size}") {
						call.respondExceptions {

							// parse args
							val sessionId = call.parameters.getOrFail("sessionId")
							val dataId = call.parameters.getOrFail("dataId")
							val size = parseSize()

							val bytes = service.readImage(sessionId, dataId, size)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}

					get("ctffind/{size}") {
						call.respondExceptions {

							// parse args
							val sessionId = call.parameters.getOrFail("sessionId")
							val dataId = call.parameters.getOrFail("dataId")
							val size = parseSize()

							val bytes = service.getCtffindImage(sessionId, dataId, size)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}
				}

				route("2dClasses/{twoDClassesId}") {

					get("image/{size}") {
						call.respondExceptions {

							// parse args
							val sessionId = call.parameters.getOrFail("sessionId")
							val twoDClassesId = call.parameters.getOrFail("twoDClassesId")
							val size = parseSize()

							val bytes = service.readTwoDClassesImage(sessionId, twoDClassesId, size)

							call.respondBytes(bytes, ContentType.Image.PNG)
						}
					}
				}
			}
		}

		private val PipelineContext<Unit, ApplicationCall>.service get() =
			getService<SessionsService>()
	}


	@Inject
	override lateinit var call: ApplicationCall


	override suspend fun canStart(): Boolean = sanitizeExceptions {
		val user = call.authOrThrow()
		return user.isAdmin || user.hasPermission(User.Permission.EditSession)
	}

	override suspend fun sessionOptions(search: String?, initial: String?, state: String?): List<RemoteOption> = sanitizeExceptions {

		val user = call.authOrThrow()

		// load the session name for the initial options, if any
		val initialOptions = if (initial != null) {
			val sessionId = initial
			listOf(RemoteOption(
				value = sessionId,
				text = Database.sessions.get(sessionId)
					?.let { Session.fromDoc(it).data(user).numberedName }
					?: "(unknown session)"
			))
		} else {
			emptyList()
		}

		val type = state
			?: return emptyList()

		// load sessions for the search query
		val searchOptions = if (search != null) {

			val normalizedSearch = search.lowercase()

			val sessions = Database.sessions.getNamesAndGroupsByType(type) { items ->
 				items
					.filter { user.isAdmin || (it.newestGroupId?.let { gid -> user.hasGroup(gid) } == true) }
					.filter { it.newestNumberedName?.lowercase()?.contains(normalizedSearch) == true }
					.toList()
			}

			sessions.map { session ->
				RemoteOption(
					value = session.sessionId,
					text = session.newestNumberedName ?: "(unnamed session)"
				)
			}
		} else {
			emptyList()
		}

		initialOptions + searchOptions
	}

	override suspend fun groups(): List<Group> = sanitizeExceptions {
		val user = call.authOrThrow()
		return getGroups(user)
	}

	private fun getGroups(user: User): List<Group> =
		if (user.isAdmin || user.hasPermission(User.Permission.EditSession)) {

			// user can see all groups
			Database.groups.getAll()

		} else {

			// show only the groups the user is a member of, if any
			user.groups.toList()
		}

	override suspend fun groupOptions(search: String?, initial: String?, state: String?): List<RemoteOption> = sanitizeExceptions {
		val user = call.authOrThrow()
		return getGroups(user)
			.filter(when {
				search != null -> {{ group -> group.name.lowercase().contains(search.lowercase()) }}
				initial != null -> {{ group -> group.id == initial }}
				else -> {{ false }}
			})
			.map { group ->
				RemoteOption(
					value = group.id,
					text = group.name
				)
			}
	}


	private fun auth(sessionId: String, permission: SessionPermission): AuthInfo<Session> =
		auth<Session>(sessionId, permission)

	override suspend fun running(): RunningSessions = sanitizeExceptions {

		val user = call.authOrThrow()

		// find all running streampyp daemons
		val jobs = ClusterJob.find(clusterName = SessionDaemon.Streampyp.clusterJobClusterName)
			.filter { it.getLog()?.status() == ClusterJob.Status.Started }

		// convert them to session data
		val sessions = jobs.mapNotNull { job ->
			job.ownerId
				?.let { Database.sessions.get(it) }
				?.let { Session.fromDoc(it) }
				?.data(user)
		}

		// split into single-particle and tomography, so we don't have to deal with polymorphic serialization
		return RunningSessions(
			singleParticle = sessions.filterIsInstance<SingleParticleSessionData>(),
			tomography = sessions.filterIsInstance<TomographySessionData>()
		)
	}

	override suspend fun isRunning(sessionId: String, daemon: SessionDaemon): Boolean = sanitizeExceptions {
		val session = auth(sessionId, SessionPermission.Read).session
		return session.isRunning(daemon)
	}

	override suspend fun start(sessionId: String, daemon: SessionDaemon) = sanitizeExceptions {
		val authed = auth(sessionId, SessionPermission.Write)
		authed.session.start(daemon)
	}

	override suspend fun restart(sessionId: String, daemon: SessionDaemon) = sanitizeExceptions {
		val session = auth(sessionId, SessionPermission.Write).session
		session.restart(daemon)
	}

	override suspend fun clear(sessionId: String, daemon: SessionDaemon) = sanitizeExceptions {
		val session = auth(sessionId, SessionPermission.Write).session
		session.clear(daemon)
	}

	override suspend fun stop(sessionId: String, daemon: SessionDaemon) = sanitizeExceptions {
		val session = auth(sessionId, SessionPermission.Write).session
		session.stop(daemon)
	}

	override suspend fun cancel(sessionId: String) = sanitizeExceptions {
		val session = auth(sessionId, SessionPermission.Write).session
		session.cancel()
	}

	override suspend fun logs(sessionId: String, daemon: SessionDaemon): SessionLogs = sanitizeExceptions {

		val session = auth(sessionId, SessionPermission.Read).session

		return SessionLogs(
			session.findDaemonJobs(daemon)
				.mapNotNull { job ->
					SessionLogData(
						jobId = job.idOrThrow,
						daemon = daemon,
						timestamp = job.getLog()?.history
							?.first { it.status == ClusterJob.Status.Submitted }
							?.timestamp
							?: return@mapNotNull null
					)
				}
		)
	}

	override suspend fun commands(sessionId: String, jobId: String): SessionCommands = sanitizeExceptions {

		val (user, session) = auth(sessionId, SessionPermission.Read)
		val job = user.authClusterJobOrThrow(session, jobId)

		return SessionCommands(
			command = job.commands.representativeCommand()
		)
	}

	override suspend fun log(sessionId: String, jobId: String): SessionLog = sanitizeExceptions {

		val (user, session) = auth(sessionId, SessionPermission.Read)
		val job = user.authClusterJobOrThrow(session, jobId)

		return SessionLog(
			log = job.getLog()?.result?.out
		)
	}

	override suspend fun speeds(sessionId: String): SessionSpeeds = sanitizeExceptions {

		val session = auth(sessionId, SessionPermission.Read).session

		return SessionSpeeds(
			transfers = session.readTransferSpeeds()?.gbps ?: emptyList()
		)
	}

	override suspend fun jobsLogs(sessionId: String): SessionJobsLogs {

		val session = auth(sessionId, SessionPermission.Read).session

		return SessionJobsLogs(
			jobs = session.findNonDaemonJobs()
				.mapNotNull { job ->
					// filter down to jobs that have finished
					job.getLog()
						?.history
						?.lastOrNull { it.status == ClusterJob.Status.Ended }
						?.let { job to it }
				}
				.map { (job, historyEntry) ->
					SessionJobLogData(
						job.idOrThrow,
						job.webName ?: "(no name)",
						historyEntry.timestamp
					)
				}
		)
	}

	override suspend fun jobLogs(sessionId: String, jobId: String): SessionJobLogs = sanitizeExceptions {

		val (user, session) = auth(sessionId, SessionPermission.Read)
		val job = user.authClusterJobOrThrow(session, jobId)

		val arraySize = job.commands.arraySize

		return SessionJobLogs(
			representativeCommand = job.commands.representativeCommand(),
			logs =
				if (arraySize != null) {
					// get the log for each array element
					(1 .. arraySize)
						.map { arrayId ->
							SessionJobLog(
								arrayId = arrayId,
								log = job.getLog(arrayId)?.result?.out
							)
						}
				} else {
					// not an array, just retun the one log
					listOf(SessionJobLog(
						arrayId = null,
						log = job.getLog()?.result?.out
					))
				}
		)
	}


	suspend fun readImage(sessionId: String, dataId: String, size: ImageSize): ByteArray {

		val session = auth(sessionId, SessionPermission.Read).session

		return when (session) {
			is SingleParticleSession -> getMicrographOrThrow(sessionId, dataId).readImage(session, size)
			is TomographySession -> getTiltSeriesOrThrow(sessionId, dataId).readImage(session, size)
		}
	}

	override suspend fun getAvgRot(sessionId: String, dataId: String): Option<AvgRotData> = sanitizeExceptions {

		val session = auth(sessionId, SessionPermission.Read).session
		val pypValues = session.pypParametersOrThrow()

		val avgrot = when (session) {
			is SingleParticleSession -> getMicrographOrThrow(sessionId, dataId).getAvgRot()
			is TomographySession -> getTiltSeriesOrThrow(sessionId, dataId).getAvgRot()
		}

		return avgrot
			.data(pypValues)
			.toOption()
	}

	override suspend fun getMotion(sessionId: String, dataId: String): Option<MotionData> = sanitizeExceptions {

		val session = auth(sessionId, SessionPermission.Read).session

		val xf = when (session) {
			is SingleParticleSession -> getMicrographOrThrow(sessionId, dataId).xf
			is TomographySession -> getTiltSeriesOrThrow(sessionId, dataId).xf
		}

		return xf
			.let { data ->
				MotionData(
					x = data.samples.map { it.x },
					y = data.samples.map { it.y }
				)
			}
			.toOption()
	}

	suspend fun getCtffindImage(sessionId: String, dataId: String, size: ImageSize): ByteArray {

		val session = auth(sessionId, SessionPermission.Read).session

		return when (session) {
			is SingleParticleSession -> getMicrographOrThrow(sessionId, dataId).readCtffindImage(session, size)
			is TomographySession -> getTiltSeriesOrThrow(sessionId, dataId).readCtffindImage(session, size)
		}
	}

	override suspend fun dataLog(sessionId: String, dataId: String): String = sanitizeExceptions {

		val session = auth(sessionId, SessionPermission.Read).session

		val logs = when (session) {
			is SingleParticleSession -> getMicrographOrThrow(sessionId, dataId).getLogs(session)
			is TomographySession -> getTiltSeriesOrThrow(sessionId, dataId).getLogs(session)
		}

		return logs
			.filter { it.timestamp != null && it.type == "pypd" }
			.maxByOrNull { it.timestamp!! }
			?.read()
			?: "(no log for ${session.type} data $dataId)"
	}

	suspend fun readTwoDClassesImage(sessionId: String, twoDClassesId: String, size: ImageSize): ByteArray {
		val session = auth(sessionId, SessionPermission.Read).session
		return when (session) {
			is SingleParticleSession -> TwoDClasses.get(sessionId, twoDClassesId)
				?.readImage(session, size)
				?: throw FileNotFoundException()
			else -> throw IllegalArgumentException("requires a single-particle session")
		}
	}

	override suspend fun listFilters(sessionId: String): List<String> = sanitizeExceptions {

		auth(sessionId, SessionPermission.Read)

		return PreprocessingFilters.ofSession(sessionId).getAll()
			.map { it.name }
	}

	override suspend fun getFilter(sessionId: String, name: String): PreprocessingFilter = sanitizeExceptions {

		auth(sessionId, SessionPermission.Read)

		return PreprocessingFilters.ofSession(sessionId)[name]
			?: throw ServiceException("no filter named $name")
	}

	override suspend fun saveFilter(sessionId: String, filter: PreprocessingFilter) = sanitizeExceptions {

		auth(sessionId, SessionPermission.Write)

		PreprocessingFilters.ofSession(sessionId).save(filter)
	}

	override suspend fun deleteFilter(sessionId: String, name: String) = sanitizeExceptions {

		auth(sessionId, SessionPermission.Write)

		PreprocessingFilters.ofSession(sessionId).delete(name)
	}

	override suspend fun filterOptions(search: String?, initial: String?, state: String?): List<RemoteOption> = sanitizeExceptions {

		val sessionId = state
			?: throw IllegalArgumentException("no session given")

		auth(sessionId, SessionPermission.Read)

		val header = listOf(
			RemoteOption(NoneFilterOption, "(None)"),
			RemoteOption(divider = true)
		)

		val options = PreprocessingFilters.ofSession(sessionId).getAll()
			.map { filter ->
				RemoteOption(filter.name)
			}

		return header + options
	}

	override suspend fun export(sessionId: String, request: String /* serialized SessionExportRequest */, slurmValues: ArgValuesToml) = sanitizeExceptions {

		val authed = auth(sessionId, SessionPermission.Write)

		val requestObj = SessionExportRequest.deserialize(request)
		val slurmArgValues = slurmValues.toArgValues(MicromonArgs.args)

		SessionExport.launch(authed.user, authed.session, requestObj, slurmArgValues)
	}

	override suspend fun cancelExport(exportId: String) = sanitizeExceptions {

		val export = SessionExport.get(exportId)
			?: return
		val session = auth(export.sessionId, SessionPermission.Write).session

		export.cancel(session)
	}
}
