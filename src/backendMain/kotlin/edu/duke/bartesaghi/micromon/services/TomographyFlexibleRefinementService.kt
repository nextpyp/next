package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import io.ktor.application.*


actual class TomographyFlexibleRefinementService : ITomographyFlexibleRefinementService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inMovieRefinements: CommonJobData.DataId, args: TomographyFlexibleRefinementArgs): TomographyFlexibleRefinementData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the job
		val job = TomographyFlexibleRefinementJob(userId, projectId)
		job.args.next = args
		job.inMovieRefinements = inMovieRefinements
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<TomographyFlexibleRefinementJob> =
		authJob(permission, this)

	override suspend fun edit(jobId: String, args: TomographyFlexibleRefinementArgs?): TomographyFlexibleRefinementData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): TomographyFlexibleRefinementData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(includeForwarded: Boolean): String = sanitizeExceptions {
		return TomographyFlexibleRefinementJob.args(includeForwarded).toJson()
	}
}
