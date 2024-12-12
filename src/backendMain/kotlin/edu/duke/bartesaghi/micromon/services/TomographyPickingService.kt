package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import io.ktor.application.*


actual class TomographyPickingService : ITomographyPickingService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyPickingArgs, copyArgs: TomographyPickingCopyArgs?): TomographyPickingData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the job
		val job = TomographyPickingJob(userId, projectId)
		job.args.next = args
		job.inTomograms = inData
		job.create()

		if (copyArgs?.copyData == true) {

			// copy data
			job.args.run()
			job.stale = false
			job.update()
			job.copyDataFrom(copyArgs.copyFromJobId)
			val project = job.projectOrThrow()
			job.copyFilesFrom(project.osUsername, copyArgs.copyFromJobId)

			// copy particles (and virions) to manual, if requested
			if (copyArgs.copyParticlesToManual) {

				// this block should only have (up to) one list
				val lists = Database.instance.particleLists.getAll(job.idOrThrow)
				when (lists.size) {
					0 -> Unit // nothing to copy
					1 -> {
						val list = lists[0]
						Database.instance.particleLists.delete(job.idOrThrow, list.name)
						Database.instance.particleLists.createIfNeeded(ParticlesList.manualParticles3D(job.idOrThrow))
						Database.instance.particles.renameAll(job.idOrThrow, list.name, ParticlesList.ManualParticles)
					}
					else -> {
						Backend.log.warn("Skipping copyParticlesToManual for jobId=${job.idOrThrow} because it has more than one particles list: ${lists.map { it.name }}")
					}
				}
			}
		}

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
