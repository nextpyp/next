package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.TiltSeries
import edu.duke.bartesaghi.micromon.sessions.AuthInfo
import edu.duke.bartesaghi.micromon.sessions.TomographySession
import edu.duke.bartesaghi.micromon.sessions.auth
import edu.duke.bartesaghi.micromon.sessions.pypNamesOrThrow
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.kvision.remote.ServiceException


actual class TomographySessionService : ITomographySessionService, Service {

	companion object {

		fun getTiltSeriesOrThrow(sessionId: String, tiltSeriesId: String): TiltSeries =
			 TiltSeries.get(sessionId, tiltSeriesId)
				?: throw NoSuchElementException("no tilt series with id $tiltSeriesId found in session $sessionId")

		fun init(routing: Routing) {

			routing.route("kv/tomographySession/{sessionId}") {

				route("{tiltSeriesId}") {

					get("alignedTiltSeriesMontage") {
						call.respondExceptions {

							// parse args
							val sessionId = call.parameters.getOrFail("sessionId")
							val tiltSeriesId = call.parameters.getOrFail("tiltSeriesId")

							val bytes = service.getAlignedTiltSeriesMontage(sessionId, tiltSeriesId)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}

					get("reconstructionTiltSeriesMontage") {
						call.respondExceptions {

							// parse args
							val sessionId = call.parameters.getOrFail("sessionId")
							val tiltSeriesId = call.parameters.getOrFail("tiltSeriesId")

							val bytes = service.getReconstructionTiltSeriesMontage(sessionId, tiltSeriesId)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}

					get("2dCtfTiltMontage") {
						call.respondExceptions {

							// parse args
							val sessionId = call.parameters.getOrFail("sessionId")
							val tiltSeriesId = call.parameters.getOrFail("tiltSeriesId")

							val bytes = service.get2dCtfTiltMontage(sessionId, tiltSeriesId)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}

					get("rawTiltSeriesMontage") {
						call.respondExceptions {

							// parse args
							val sessionId = call.parameters.getOrFail("sessionId")
							val tiltSeriesId = call.parameters.getOrFail("tiltSeriesId")

							val bytes = service.getRawTiltSeriesMontage(sessionId, tiltSeriesId)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}

					get("sidesTiltSeriesImage") {
						call.respondExceptions {

							// parse args
							val sessionId = call.parameters.getOrFail("sessionId")
							val tiltSeriesId = call.parameters.getOrFail("tiltSeriesId")

							val bytes = service.getSidesTiltSeriesImage(sessionId, tiltSeriesId)

							call.respondBytes(bytes, ContentType.Image.WebP)
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

		// create the session
		val session = TomographySession(user.id)
		session.args.next = args
		session.create()

		return session.data(user)
	}

	override suspend fun edit(sessionId: String, args: TomographySessionArgs?): TomographySessionData = sanitizeExceptions {

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

	override suspend fun getArgs(includeForwarded: Boolean): String = sanitizeExceptions {
		call.authOrThrow()
		return TomographySession.args(includeForwarded).toJson()
	}

	fun getAlignedTiltSeriesMontage(sessionId: String, tiltSeriesId: String): ByteArray {

		val session = auth(sessionId, SessionPermission.Read).session
		val tiltSeries = getTiltSeriesOrThrow(sessionId, tiltSeriesId)

		return tiltSeries.getAlignedTiltSeriesMontage(session.pypDir(session.newestArgs().pypNamesOrThrow()))
	}

	fun getReconstructionTiltSeriesMontage(sessionId: String, tiltSeriesId: String): ByteArray {

		val session = auth(sessionId, SessionPermission.Read).session
		val tiltSeries = getTiltSeriesOrThrow(sessionId, tiltSeriesId)

		return tiltSeries.getReconstructionTiltSeriesMontage(session.pypDir(session.newestArgs().pypNamesOrThrow()))
	}

	fun get2dCtfTiltMontage(sessionId: String, tiltSeriesId: String): ByteArray {

		val session = auth(sessionId, SessionPermission.Read).session
		val tiltSeries = getTiltSeriesOrThrow(sessionId, tiltSeriesId)

		return tiltSeries.get2dCtfTiltMontage(session.pypDir(session.newestArgs().pypNamesOrThrow()))
	}

	fun getRawTiltSeriesMontage(sessionId: String, tiltSeriesId: String): ByteArray {

		val session = auth(sessionId, SessionPermission.Read).session
		val tiltSeries = getTiltSeriesOrThrow(sessionId, tiltSeriesId)

		return tiltSeries.getRawTiltSeriesMontage(session.pypDir(session.newestArgs().pypNamesOrThrow()))
	}

	fun getSidesTiltSeriesImage(sessionId: String, tiltSeriesId: String): ByteArray {

		val session = auth(sessionId, SessionPermission.Read).session
		val tiltSeries = getTiltSeriesOrThrow(sessionId, tiltSeriesId)

		return tiltSeries.getSidesTiltSeriesImage(session.pypDir(session.newestArgs().pypNamesOrThrow()))
	}

	override suspend fun copy(sessionId: String, args: CopySessionArgs): TomographySessionData = sanitizeExceptions {

		val (user, session) = auth(sessionId, SessionPermission.Write)

		// make a copy of the session and save it
		val newSession = TomographySession(user.id)
		newSession.args.next = session.args.newestOrThrow().args
			.copy(name = args.name)
		newSession.create()

		return newSession.data(user)
	}

	override suspend fun getDriftMetadata(sessionId: String, tiltSeriesId: String): Option<DriftMetadata> {
		val session = auth(sessionId, SessionPermission.Read).session
		val pypValues = session.pypParameters()
			?: return  null.toOption()
		return getTiltSeriesOrThrow(sessionId, tiltSeriesId)
			.getDriftMetadata(pypValues)
			.toOption()
	}
}
