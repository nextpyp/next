package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import io.ktor.application.*


actual class TomographyPickingService : ITomographyPickingService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyPickingArgs): TomographyPickingData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// figure out what kind of input we got
		val input = inData.resolveJob<Job>()
			.baseConfig
			.getOutputOrThrow(inData.dataId)

		// make the job
		val job = TomographyPickingJob(userId, projectId)
		job.args.next = args
		when (input.type) {
			NodeConfig.Data.Type.Tomograms -> job.inTomograms = inData
			NodeConfig.Data.Type.Segmentation -> job.inSegmentation = inData
			else -> throw IllegalArgumentException("Unxepected input data type: ${input.type}")
		}
		job.create(user)

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<TomographyPickingJob> =
		authJob(permission, this)

	override suspend fun edit(jobId: String, args: TomographyPickingArgs?): TomographyPickingData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): TomographyPickingData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return TomographyPickingJob.args().toJson()
	}
}
