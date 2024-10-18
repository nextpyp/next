package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.*
import io.ktor.application.*


actual class SingleParticleDrgnService : ISingleParticleDrgnService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: SingleParticleDrgnArgs): SingleParticleDrgnData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the job
		val job = SingleParticleDrgnJob(userId, projectId)
		job.args.next = args
		job.inRefinements = inData
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<SingleParticleDrgnJob> =
		authJob(permission, this)

	override suspend fun edit(jobId: String, args: SingleParticleDrgnArgs?): SingleParticleDrgnData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): SingleParticleDrgnData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(includeForwarded: Boolean): String = sanitizeExceptions {
		return SingleParticleDrgnJob.args(includeForwarded).toJson()
	}
}
