package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import io.ktor.application.*


actual class SingleParticleFlexibleRefinementService : ISingleParticleFlexibleRefinementService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inRefinements: CommonJobData.DataId, args: SingleParticleFlexibleRefinementArgs): SingleParticleFlexibleRefinementData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the job
		val job = SingleParticleFlexibleRefinementJob(userId, projectId)
		job.args.next = args
		job.inRefinements = inRefinements
		job.create(user)

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<SingleParticleFlexibleRefinementJob> =
		authJob(permission, this)

	override suspend fun edit(jobId: String, args: SingleParticleFlexibleRefinementArgs?): SingleParticleFlexibleRefinementData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): SingleParticleFlexibleRefinementData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return SingleParticleFlexibleRefinementJob.args().toJson()
	}
}
