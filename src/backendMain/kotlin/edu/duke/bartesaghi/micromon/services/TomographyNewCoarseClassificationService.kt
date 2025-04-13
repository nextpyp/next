package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import io.ktor.application.*


actual class TomographyNewCoarseClassificationService : ITomographyNewCoarseClassificationService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inMovieRefinement: CommonJobData.DataId, args: TomographyNewCoarseClassificationArgs): TomographyNewCoarseClassificationData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the job
		val job = TomographyNewCoarseClassificationJob(userId, projectId)
		job.args.next = args
		job.inMovieRefinement = inMovieRefinement
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<TomographyNewCoarseClassificationJob> =
		authJob(this, permission)

	override suspend fun edit(jobId: String, args: TomographyNewCoarseClassificationArgs?): TomographyNewCoarseClassificationData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): TomographyNewCoarseClassificationData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return TomographyNewCoarseClassificationJob.args().toJson()
	}
}
