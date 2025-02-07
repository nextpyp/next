package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.allowedPathOrThrow
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.TiltSeries
import edu.duke.bartesaghi.micromon.sessions.*
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.kvision.remote.ServiceException


actual class TomographySessionService : ITomographySessionService, Service {

	companion object {

		fun getTiltSeriesOrThrow(session: Session, tiltSeriesId: String): TiltSeries =
			 TiltSeries.get(session.idOrThrow, tiltSeriesId)
				?: throw NoSuchElementException("no tilt series with id $tiltSeriesId found in session ${session.idOrThrow}")

		fun init(routing: Routing) {

			routing.route("kv/tomographySession/{sessionId}") {

				fun PipelineContext<Unit, ApplicationCall>.authSession(permission: SessionPermission): AuthInfo<Session> {
					val sessionId = call.parameters.getOrFail("sessionId")
					return call.authSession(sessionId, permission)
				}

				route("{tiltSeriesId}") {

					get("alignedTiltSeriesMontage") {
						call.respondExceptions {

							// parse args
							val session = authSession(SessionPermission.Read).session
							val tiltSeriesId = call.parameters.getOrFail("tiltSeriesId")

							// serve the image
							val imagePath = TiltSeries.alignedMontagePath(session.dir, tiltSeriesId)
							val imageType = ImageType.Webp
							call.respondImage(imagePath, imageType)
						}
					}

					get("reconstructionTiltSeriesMontage") {
						call.respondExceptions {

							// parse args
							val session = authSession(SessionPermission.Read).session
							val tiltSeriesId = call.parameters.getOrFail("tiltSeriesId")

							// serve the image
							val imagePath = TiltSeries.reconstructionTiltSeriesMontagePath(session.dir, tiltSeriesId)
							val imageType = ImageType.Webp
							call.respondImage(imagePath, imageType)
						}
					}

					get("2dCtfTiltMontage") {
						call.respondExceptions {

							// parse args
							val session = authSession(SessionPermission.Read).session
							val tiltSeriesId = call.parameters.getOrFail("tiltSeriesId")

							// serve the image
							val imagePath = TiltSeries.twodCtfTiltMontagePath(session.dir, tiltSeriesId)
							val imageType = ImageType.Webp
							call.respondImage(imagePath, imageType)

						}
					}

					get("rawTiltSeriesMontage") {
						call.respondExceptions {

							// parse args
							val session = authSession(SessionPermission.Read).session
							val tiltSeriesId = call.parameters.getOrFail("tiltSeriesId")

							// serve the image
							val imagePath = TiltSeries.rawTiltSeriesMontagePath(session.dir, tiltSeriesId)
							val imageType = ImageType.Webp
							call.respondImage(imagePath, imageType)
						}
					}

					get("sidesTiltSeriesImage") {
						call.respondExceptions {

							// parse args
							val session = authSession(SessionPermission.Read).session
							val tiltSeriesId = call.parameters.getOrFail("tiltSeriesId")

							// serve the image
							val imagePath = TiltSeries.sidesImagePath(session.dir, tiltSeriesId)
							val imageType = ImageType.Webp
							call.respondImage(imagePath, imageType)
						}
					}

					get("rec") {
						call.respondExceptions {

							// parse args
							val session = authSession(SessionPermission.Read).session
							val tiltSeriesId = call.parameters.getOrFail("tiltSeriesId")

							val path = TiltSeries.recPath(session.dir, tiltSeriesId)
							call.respondFile(path, ContentType.Application.OctetStream)
						}
					}
				}
			}
		}

		private val PipelineContext<Unit, ApplicationCall>.service get() =
			getService<TomographySessionService>()
	}


	@Inject
	override lateinit var call: ApplicationCall


	private val sessionRoots = listOf(Session.defaultDir())


	fun auth(sessionId: String, permission: SessionPermission): AuthInfo<TomographySession> =
		auth<TomographySession>(sessionId, permission)

	override suspend fun list(): List<TomographySessionData> = sanitizeExceptions {

		val user = call.authOrThrow()

		return Database.instance.sessions.getGroupsByType(TomographySession.id) { cursor ->
			cursor
				.filter { user.isAdmin || (it.newestGroupId?.let { gid -> user.hasGroup(gid) } == true) }
				.mapNotNull { TomographySession.fromId(it.sessionId) }
				.filter { it.isReadableBy(user) }
				.map { it.data(user) }
				.toList()
		}
	}

	override suspend fun create(args: TomographySessionArgs): TomographySessionData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authPermissionOrThrow(User.Permission.EditSession)

		// make sure the user can choose that path
		args.path.allowedPathOrThrow(user, extraAllowedRoots=sessionRoots)

		// create the session
		val session = TomographySession(user.id)
		session.args.next = args
		session.create()

		return session.data(user)
	}

	override suspend fun edit(sessionId: String, args: TomographySessionArgs): TomographySessionData = sanitizeExceptions {

		val (user, session) = auth(sessionId, SessionPermission.Write)
		session.args.next = args
		session.update()

		return session.data(user)
	}

	override suspend fun get(sessionId: String): TomographySessionData = sanitizeExceptions {
		val (user, session) = auth(sessionId, SessionPermission.Read)
		return session.data(user)
	}

	override suspend fun delete(sessionId: String) = sanitizeExceptions {

		val session = auth(sessionId, SessionPermission.Write).session

		// don't delete sessions that are currently running
		if (SessionDaemon.values().any { session.isRunning(it) }) {
			throw ServiceException("Session is currently running and can't be deleted. Stop the session before deleting it.")
		}

		session.delete()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		call.authOrThrow()
		return TomographySession.args().toJson()
	}

	override suspend fun copy(sessionId: String, args: CopySessionArgs): TomographySessionData = sanitizeExceptions {

		val (user, session) = auth(sessionId, SessionPermission.Write)

		// make sure the user can choose that path
		args.path.allowedPathOrThrow(user, extraAllowedRoots=sessionRoots)

		// make a copy of the session and save it
		val newSession = TomographySession(user.id)
		newSession.args.next = session.args.newestOrThrow().args
			.copy(
				name = args.name,
				path = args.path
			)
		newSession.create()

		return newSession.data(user)
	}

	override suspend fun getDriftMetadata(sessionId: String, tiltSeriesId: String): Option<DriftMetadata> {
		val session = auth(sessionId, SessionPermission.Read).session
		val pypValues = session.pypParameters()
			?: return  null.toOption()
		return getTiltSeriesOrThrow(session, tiltSeriesId)
			.getDriftMetadata(pypValues)
			.toOption()
	}

	override suspend fun recData(sessionId: String, tiltSeriesId: String): Option<FileDownloadData> = sanitizeExceptions {
		val session = auth(sessionId, SessionPermission.Read).session
		TiltSeries.recPath(session.dir, tiltSeriesId)
			.toFileDownloadData()
			.toOption()
	}
}
