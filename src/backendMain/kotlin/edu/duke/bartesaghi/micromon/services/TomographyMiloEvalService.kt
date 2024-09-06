package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import edu.duke.bartesaghi.micromon.nodes.TomographyMiloEvalNodeConfig
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlin.io.path.div


actual class TomographyMiloEvalService : ITomographyMiloEvalService, Service {

	companion object {

		fun init(routing: Routing) {

			routing.route("kv/node/${TomographyMiloEvalNodeConfig.ID}/{jobId}") {

				fun PipelineContext<Unit, ApplicationCall>.parseJobId(): String =
					call.parameters.getOrFail("jobId")



				get("results_2d/{size}") {
					call.respondExceptions {

						// parse args
						val jobId = parseJobId()
						val size = parseSize()

						val bytes = service.getResults2d(jobId, size)

						call.respondBytes(bytes, ContentType.Image.WebP)
					}
				}

				get("results_2d_labels/{size}") {
					call.respondExceptions {

						// parse args
						val jobId = parseJobId()
						val size = parseSize()

						val bytes = service.getResults2dLabels(jobId, size)

						call.respondBytes(bytes, ContentType.Image.WebP)
					}
				}

				get("results_3d/{size}") {
					call.respondExceptions {

						// parse args
						val jobId = parseJobId()
						val size = parseSize()

						val bytes = service.getResults3d(jobId, size)

						call.respondBytes(bytes, ContentType.Image.WebP)
					}
				}

				get("data") {
					call.respondExceptions {

						// parse args
						val jobId = parseJobId()

						val bytes = service.dataContent(jobId)
						call.respondBytes(bytes, ContentType.Application.OctetStream)
					}
				}

				route("upload_particles", FileUpload.routeHandler { permission ->
					val job = service.run {
						parseJobId().authJob(permission).job
					}
					val project = job.projectOrThrow()
					FileUpload(job.dir / "particles.parquet", project.osUsername)
				})
			}
		}

		private val PipelineContext<Unit, ApplicationCall>.service get() =
			getService<TomographyMiloEvalService>()
	}


	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyMiloEvalArgs): TomographyMiloEvalData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the job
		val job = TomographyMiloEvalJob(userId, projectId)
		job.args.next = args
		job.inModel = inData
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<TomographyMiloEvalJob> =
		authJob(permission, this)

	override suspend fun edit(jobId: String, args: TomographyMiloEvalArgs?): TomographyMiloEvalData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): TomographyMiloEvalData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return TomographyMiloEvalJob.args().toJson()
	}

	suspend fun getResults2d(jobId: String, size: ImageSize): ByteArray {

		val job = jobId.authJob(ProjectPermission.Read).job

		val imagePath = job.dir / "train" / "2d_visualization_out.webp"
		val cacheInfo = ImageCacheInfo(job.wwwDir, "milo-results-2d")

		return size.readResize(imagePath, ImageType.Webp, cacheInfo)
		// no image, return a placeholder
			?: Resources.placeholderJpg(size)
	}

	suspend fun getResults2dLabels(jobId: String, size: ImageSize): ByteArray {

		val job = jobId.authJob(ProjectPermission.Read).job

		val imagePath = job.dir / "train" / "2d_visualization_labels.webp"
		val cacheInfo = ImageCacheInfo(job.wwwDir, "milo-results-2d-labels")

		return size.readResize(imagePath, ImageType.Webp, cacheInfo)
		// no image, return a placeholder
			?: Resources.placeholderJpg(size)
	}

	suspend fun getResults3d(jobId: String, size: ImageSize): ByteArray {

		val job = jobId.authJob(ProjectPermission.Read).job

		val imagePath = job.dir / "train" / "3d_visualization_out.webp"
		val cacheInfo = ImageCacheInfo(job.wwwDir, "milo-results-3d")

		return size.readResize(imagePath, ImageType.Webp, cacheInfo)
		// no image, return a placeholder
			?: Resources.placeholderJpg(size)
	}

	override suspend fun data(jobId: String): Option<FileDownloadData> = sanitizeExceptions {
		val job = jobId.authJob(ProjectPermission.Read).job
		val path = job.dir / "train" / "milopyp_interactive.tbz"
		path
			.toFileDownloadData()
			.toOption()
	}

	fun dataContent(jobId: String): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job
		val path = job.dir / "train" / "milopyp_interactive.tbz"
		return path.readBytes()
	}

}
