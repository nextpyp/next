package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.sessions.AuthInfo
import edu.duke.bartesaghi.micromon.sessions.SingleParticleSession
import edu.duke.bartesaghi.micromon.sessions.auth
import io.ktor.application.*
import io.kvision.remote.ServiceException


actual class SingleParticleSessionService : ISingleParticleSessionService, Service {

	@Inject
	override lateinit var call: ApplicationCall


	fun auth(sessionId: String, permission: SessionPermission): AuthInfo<SingleParticleSession> =
		auth<SingleParticleSession>(sessionId, permission)

	override suspend fun list(): List<SingleParticleSessionData> = sanitizeExceptions {

		val user = call.authOrThrow()

		return Database.sessions.getGroupsByType(SingleParticleSession.id) { cursor ->
			cursor
				.filter { user.isAdmin || (it.newestGroupId?.let { gid -> user.hasGroup(gid) } == true) }
				.mapNotNull { SingleParticleSession.fromId(it.sessionId) }
				.filter { it.isReadableBy(user) }
				.map { it.data(user) }
				.toList()
		}
	}

	override suspend fun create(args: SingleParticleSessionArgs): SingleParticleSessionData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authPermissionOrThrow(User.Permission.EditSession)

		// create the session
		val session = SingleParticleSession(user.id)
		session.args.next = args
		session.create()

		return session.data(user)
	}

	override suspend fun edit(sessionId: String, args: SingleParticleSessionArgs?): SingleParticleSessionData = sanitizeExceptions {

		val (user, session) = auth(sessionId, SessionPermission.Write)
		session.args.next = args
		session.update()

		return session.data(user)
	}

	override suspend fun get(sessionId: String): SingleParticleSessionData = sanitizeExceptions {
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
		return SingleParticleSession.args(includeForwarded).toJson()
	}

	override suspend fun copy(sessionId: String, args: CopySessionArgs): SingleParticleSessionData = sanitizeExceptions {

		val (user, session) = auth(sessionId, SessionPermission.Write)

		// make a copy of the session and save it
		val newSession = SingleParticleSession(user.id)
		newSession.args.next = session.args.newestOrThrow().args
			.copy(name = args.name)
		newSession.create()

		return newSession.data(user)
	}
}
