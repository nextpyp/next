package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import io.ktor.application.*


actual class SingleParticleRelionDataService : ISingleParticleRelionDataService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun import(userId: String, projectId: String, args: SingleParticleRelionDataArgs): SingleParticleRelionDataData = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// TODO: access control list check for this user on this dir
		//args.moviesDir

		// make the job
		val job = SingleParticleRelionDataJob(userId, projectId)
		job.args.next = args
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<SingleParticleRelionDataJob> =
		authJob(this, permission)

	override suspend fun edit(jobId: String, args: SingleParticleRelionDataArgs?): SingleParticleRelionDataData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): SingleParticleRelionDataData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return SingleParticleRelionDataJob.args().toJson()
	}
}
