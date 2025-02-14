package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.linux.userprocessor.WebCacheDir
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.sessions.*
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.kvision.remote.RemoteOption
import io.kvision.remote.ServiceException
import kotlin.io.path.div


actual class SessionsService : ISessionsService, Service {

	companion object {

		private fun getMicrographOrThrow(session: Session, micrographId: String): Micrograph =
			Micrograph.get(session.idOrThrow, micrographId)
				?: throw NoSuchElementException("no micrograph with id $micrographId found in session ${session.idOrThrow}")

		private fun getTiltSeriesOrThrow(session: Session, tiltSeriesId: String): TiltSeries =
			TiltSeries.get(session.idOrThrow, tiltSeriesId)
				?: throw NoSuchElementException("no tilt series with id $tiltSeriesId found in session ${session.idOrThrow}")

		fun init(routing: Routing) {

			routing.route("kv/sessions/{sessionId}") {

				fun PipelineContext<Unit, ApplicationCall>.authSession(permission: SessionPermission): AuthInfo<Session> {
					val sessionId = call.parameters.getOrFail("sessionId")
					return call.authSession(sessionId, permission)
				}

				route("data/{dataId}") {

					get("image/{size}") {
						call.respondExceptions {

							// parse args
							val session = authSession(SessionPermission.Read).session
							val dataId = call.parameters.getOrFail("dataId")
							val size = parseSize()

							// serve the image
							val imagePath = when (session) {
								is SingleParticleSession -> Micrograph.outputImagePath(session.dir, dataId)
								is TomographySession -> TiltSeries.outputImagePath(session.dir, dataId)
							}
							val cacheKey = WebCacheDir.Keys.datum.parameterized(dataId)
							ImageType.Webp.respondSized(call, imagePath, size.info(session.wwwDir, cacheKey))
								?.respondPlaceholder(call, size)
						}
					}

					get("ctffind/{size}") {
						call.respondExceptions {

							// parse args
							val session = authSession(SessionPermission.Read).session
							val dataId = call.parameters.getOrFail("dataId")
							val size = parseSize()

							// serve the image
							val (imagePath, transformer) = when (session) {
								is SingleParticleSession ->
									Micrograph.ctffitImagePath(session.dir, dataId) to null
								is TomographySession ->
									TiltSeries.twodCtfTiltMontagePath(session.dir, dataId) to TiltSeries.montageCenterTiler(session.idOrThrow, dataId)
							}
							val cacheKey = WebCacheDir.Keys.ctffit.parameterized(dataId)
							ImageType.Webp.respondSized(call, imagePath, size.info(session.wwwDir, cacheKey, transformer))
								?.respondPlaceholder(call, size)
						}
					}
				}

				route("2dClasses/{twoDClassesId}") {

					get("image/{size}") {
						call.respondExceptions {

							// parse args
							val session = authSession(SessionPermission.Read).session
							val twoDClassesId = call.parameters.getOrFail("twoDClassesId")
							val size = parseSize()

							val imagePath = when (session) {
								is SingleParticleSession -> TwoDClasses.imagePath(session, twoDClassesId)
								else -> throw BadRequestException("requires a single-particle session")
							}
							val cacheKey = WebCacheDir.Keys.twodClasses.parameterized(twoDClassesId)
							ImageType.Webp.respondSized(call, imagePath, size.info(session.wwwDir, cacheKey))
								?.respondPlaceholder(call, size)
						}
					}
				}
			}
		}
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
				text = Database.instance.sessions.get(sessionId)
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

			val sessions = Database.instance.sessions.getNamesAndGroupsByType(type) { items ->
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
			Database.instance.groups.getAll()

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
				?.let { Database.instance.sessions.get(it) }
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
						clusterJobId = job.idOrThrow,
						daemon = daemon,
						timestamp = job.getLog()?.history
							?.first { it.status == ClusterJob.Status.Submitted }
							?.timestamp
							?: return@mapNotNull null
					)
				}
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

	override suspend fun getAvgRot(sessionId: String, dataId: String): Option<AvgRotData> = sanitizeExceptions {

		val session = auth(sessionId, SessionPermission.Read).session
		val pypValues = session.pypParametersOrThrow()

		val avgrot = when (session) {
			is SingleParticleSession -> getMicrographOrThrow(session, dataId).getAvgRot()
			is TomographySession -> getTiltSeriesOrThrow(session, dataId).getAvgRot()
		}

		return avgrot
			.data(pypValues)
			.toOption()
	}

	override suspend fun getMotion(sessionId: String, dataId: String): Option<MotionData> = sanitizeExceptions {

		val session = auth(sessionId, SessionPermission.Read).session

		val xf = when (session) {
			is SingleParticleSession -> getMicrographOrThrow(session, dataId).xf
			is TomographySession -> getTiltSeriesOrThrow(session, dataId).xf
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

	override suspend fun dataLog(sessionId: String, dataId: String): String = sanitizeExceptions {

		val session = auth(sessionId, SessionPermission.Read).session

		val logs = when (session) {
			is SingleParticleSession -> getMicrographOrThrow(session, dataId).getLogs(session)
			is TomographySession -> getTiltSeriesOrThrow(session, dataId).getLogs(session)
		}

		return logs
			.filter { it.timestamp != null && it.type == "pypd" }
			.maxByOrNull { it.timestamp!! }
			?.read()
			?: "(no log for ${session.type} data $dataId)"
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
			?: throw BadRequestException("no session given")

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

	override suspend fun pickFolder(): String = sanitizeExceptions {

		// NOTE: Be sure to check session create permissions here, like the create function would,
		//       since this function could be used to mine information about the filesytem remotely,
		//       so we want to make sure it's only used by authorized people.
		val user = call.authOrThrow()
		user.authPermissionOrThrow(User.Permission.EditSession)

		// all good: look at the files in the sessions folder and pick a unique name
		val baseName = "new_session"
		fun makePath(name: String) =
			Session.defaultDir() / name

		var path = makePath(baseName)
		var counter = 1
		val numTries = 1000
		for (i in 0 until numTries) {

			if (!path.exists()) {
				return path.toString()
			}

			// already exists: pick a name variant (by adding _c to the end) and try again
			counter += 1
			path = makePath("${baseName}_$counter")
		}

		// no unique name found after a lot of tries
		throw ServiceException("Failed to find a unique folder for the session after $numTries attempts")
	}
}
