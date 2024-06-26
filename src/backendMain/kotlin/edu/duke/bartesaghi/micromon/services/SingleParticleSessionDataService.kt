package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.*
import edu.duke.bartesaghi.micromon.sessions.authSessionForReadOrThrow
import io.ktor.application.*


actual class SingleParticleSessionDataService : ISingleParticleSessionDataService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun import(userId: String, projectId: String, sessionId: String): SingleParticleSessionDataData = sanitizeExceptions {

		// authenticate the user for this project and session
		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)
		user.authSessionForReadOrThrow(sessionId)

		// make the job
		val job = SingleParticleSessionDataJob(userId, projectId)
		job.args.next = SingleParticleSessionDataArgs(sessionId,"", null)
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<SingleParticleSessionDataJob> =
		authJob(permission, this)

	override suspend fun edit(jobId: String, args: SingleParticleSessionDataArgs?): SingleParticleSessionDataData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): SingleParticleSessionDataData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return SingleParticleSessionDataJob.args().toJson()
	}
}
