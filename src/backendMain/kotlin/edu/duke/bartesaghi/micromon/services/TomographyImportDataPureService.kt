package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.*
import edu.duke.bartesaghi.micromon.nodes.TomographyImportDataPureNodeConfig
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import javax.imageio.ImageIO
import kotlin.io.path.div


actual class TomographyImportDataPureService : ITomographyImportDataPureService, Service {

	companion object {

		fun imageUrl(jobId: String): String =
			"/kv/node/${TomographyImportDataPureNodeConfig.ID}/$jobId/image"

		fun imageUrl(jobId: String, size: ImageSize): String =
			"${imageUrl(jobId)}/${size.id}"

		fun init(routing: Routing) {

			routing.route("kv/node/${TomographyImportDataPureNodeConfig.ID}/{jobId}") {

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
			getService<TomographyImportDataPureService>()
	}

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun import(userId: String, projectId: String, args: TomographyImportDataPureArgs): TomographyImportDataPureData = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// TODO: access control list check for this user on this dir
		//args.tiltSeriesDir

		// make the job
		val job = TomographyImportDataPureJob(userId, projectId)
		job.args.next = args
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<TomographyImportDataPureJob> =
		authJob(permission, this)

	override suspend fun edit(jobId: String, args: TomographyImportDataPureArgs?): TomographyImportDataPureData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): TomographyImportDataPureData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return TomographyImportDataPureJob.args().toJson()
	}

	suspend fun getImage(jobId: String, size: ImageSize): ByteArray {

		val job = jobId.authJob(ProjectPermission.Read).job

		val sourcePath = job.dir / "gain_corrected.webp"
		val cacheInfo = ImageCacheInfo(job.wwwDir, "gain-corrected")

		return size.readResize(sourcePath, ImageType.Webp, cacheInfo)
			// no image, return a placeholder
			?: Resources.placeholderJpg(size)
	}
}
