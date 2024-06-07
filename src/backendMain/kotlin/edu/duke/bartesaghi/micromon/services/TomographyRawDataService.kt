package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import io.ktor.application.*


actual class TomographyRawDataService : ITomographyRawDataService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun import(userId: String, projectId: String, args: TomographyRawDataArgs): TomographyRawDataData = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// TODO: access control list check for this user on this dir
		//args.tiltSeriesDir

		// make the job
		val job = TomographyRawDataJob(userId, projectId)
		job.args.next = args
		job.create(user)

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<TomographyRawDataJob> =
		authJob(permission, this)

	override suspend fun edit(jobId: String, args: TomographyRawDataArgs?): TomographyRawDataData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): TomographyRawDataData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return TomographyRawDataJob.args().toJson()
	}
}
