package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.*
import edu.duke.bartesaghi.micromon.sessions.authSessionForReadOrThrow
import io.ktor.application.*


actual class TomographySessionDataService : ITomographySessionDataService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun import(userId: String, projectId: String, sessionId: String): TomographySessionDataData = sanitizeExceptions {

		// authenticate the user for this project and session
		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)
		user.authSessionForReadOrThrow(sessionId)

		// make the job
		val job = TomographySessionDataJob(userId, projectId)
		job.args.next = TomographySessionDataArgs(sessionId,"", null)
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<TomographySessionDataJob> =
		authJob(this, permission)

	override suspend fun edit(jobId: String, args: TomographySessionDataArgs?): TomographySessionDataData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): TomographySessionDataData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return TomographySessionDataJob.args().toJson()
	}
}
