package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.WebCacheDir
import edu.duke.bartesaghi.micromon.mongo.*
import edu.duke.bartesaghi.micromon.nodes.SingleParticleImportDataNodeConfig
import io.ktor.application.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlin.io.path.div


actual class SingleParticleImportDataService : ISingleParticleImportDataService, Service {

	companion object {

		fun imageUrl(jobId: String): String =
			"/kv/node/${SingleParticleImportDataNodeConfig.ID}/$jobId/image"

		fun imageUrl(jobId: String, size: ImageSize): String =
			"${imageUrl(jobId)}/${size.id}"

		fun init(routing: Routing) {

			routing.route("kv/node/${SingleParticleImportDataNodeConfig.ID}/{jobId}") {

				fun PipelineContext<Unit, ApplicationCall>.authJob(permission: ProjectPermission): AuthInfo<Job> {
					val jobId = call.parameters.getOrFail("jobId")
					return call.authJob(jobId, permission)
				}

				get("image/{size}") {
					call.respondExceptions {

						// parse args
						val job = authJob(ProjectPermission.Read).job
						val size = parseSize()

						// serve the image
						val imagePath = job.dir / "gain_corrected.webp"
						val cacheKey = WebCacheDir.Keys.gainCorrected
						ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
							?.respondPlaceholder(call, size)
					}
				}
			}
		}

		private val PipelineContext<Unit,ApplicationCall>.service get() =
			getService<SingleParticleImportDataService>()
	}

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun import(userId: String, projectId: String, args: SingleParticleImportDataArgs): SingleParticleImportDataData = sanitizeExceptions {

		// authenticate the user for this project and session
		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)
		
		// make the job
		val job = SingleParticleImportDataJob(userId, projectId)
		job.args.next = args
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<SingleParticleImportDataJob> =
		authJob(this, permission)

	override suspend fun edit(jobId: String, args: SingleParticleImportDataArgs?): SingleParticleImportDataData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): SingleParticleImportDataData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return SingleParticleImportDataJob.args().toJson()
	}
}
