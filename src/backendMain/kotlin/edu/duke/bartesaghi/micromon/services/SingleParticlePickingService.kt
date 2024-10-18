package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.*
import io.ktor.application.*


actual class SingleParticlePickingService : ISingleParticlePickingService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: SingleParticlePickingArgs): SingleParticlePickingData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the job
		val job = SingleParticlePickingJob(userId, projectId)
		job.args.next = args
		job.inMicrographs = inData
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<SingleParticlePickingJob> =
		authJob(permission, this)

	override suspend fun edit(jobId: String, args: SingleParticlePickingArgs?): SingleParticlePickingData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): SingleParticlePickingData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(includeForwarded: Boolean): String = sanitizeExceptions {
		return SingleParticlePickingJob.args(includeForwarded).toJson()
	}
}
