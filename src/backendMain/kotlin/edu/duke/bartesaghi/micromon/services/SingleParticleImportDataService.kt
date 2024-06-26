package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.*
import edu.duke.bartesaghi.micromon.nodes.SingleParticleImportDataNodeConfig
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
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

				get("image/{size}") {
					call.respondExceptions {

						// parse args
						val jobId = call.parameters.getOrFail("jobId")
						val size = parseSize()

						val bytes = service.getImage(jobId, size)

						call.respondBytes(bytes, ContentType.Image.WebP)
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
		authJob(permission, this)

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

	suspend fun getImage(jobId: String, size: ImageSize): ByteArray {

		val job = jobId.authJob(ProjectPermission.Write).job

		val sourcePath = job.dir / "gain_corrected.webp"
		val cacheInfo = ImageCacheInfo(job.wwwDir, "gain-corrected")

		return size.readResize(sourcePath, ImageType.Webp, cacheInfo)
			// no image, return a placeholder
			?: Resources.placeholderJpg(size)
	}
}
