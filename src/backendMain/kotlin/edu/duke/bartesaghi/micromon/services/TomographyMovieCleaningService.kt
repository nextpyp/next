package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import io.ktor.application.ApplicationCall


actual class TomographyMovieCleaningService : ITomographyMovieCleaningService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inMovieRefinements: CommonJobData.DataId, args: TomographyMovieCleaningArgs): TomographyMovieCleaningData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the job
		val job = TomographyMovieCleaningJob(userId, projectId)
		job.args.next = args
		job.inMovieRefinements = inMovieRefinements
		job.create(user)

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<TomographyMovieCleaningJob> =
		authJob(permission, this)

	override suspend fun edit(jobId: String, args: TomographyMovieCleaningArgs?): TomographyMovieCleaningData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): TomographyMovieCleaningData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return TomographyMovieCleaningJob.args().toJson()
	}
}
