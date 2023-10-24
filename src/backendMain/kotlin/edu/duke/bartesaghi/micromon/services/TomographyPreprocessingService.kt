package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import io.ktor.application.*


actual class TomographyPreprocessingService : ITomographyPreprocessingService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inTiltSeries: CommonJobData.DataId, args: TomographyPreprocessingArgs): TomographyPreprocessingData = sanitizeExceptions {

		call.authOrThrow()
			.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the job
		val job = TomographyPreprocessingJob(userId, projectId)
		job.args.next = args
		job.inTiltSeries = inTiltSeries
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<TomographyPreprocessingJob> =
		authJob(permission, this)

	override suspend fun edit(jobId: String, args: TomographyPreprocessingArgs?): TomographyPreprocessingData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): TomographyPreprocessingData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return TomographyPreprocessingJob.args().toJson()
	}
}
