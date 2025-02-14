package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.WebCacheDir
import edu.duke.bartesaghi.micromon.mongo.*
import edu.duke.bartesaghi.micromon.nodes.TomographyImportDataNodeConfig
import io.ktor.application.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlin.io.path.div


actual class TomographyImportDataService : ITomographyImportDataService, Service {

	companion object {

		fun imageUrl(jobId: String): String =
			"/kv/node/${TomographyImportDataNodeConfig.ID}/$jobId/image"

		fun imageUrl(jobId: String, size: ImageSize): String =
			"${imageUrl(jobId)}/${size.id}"

		fun init(routing: Routing) {

			routing.route("kv/node/${TomographyImportDataNodeConfig.ID}/{jobId}") {

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
			getService<TomographyImportDataService>()
	}

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun import(userId: String, projectId: String, args: TomographyImportDataArgs): TomographyImportDataData = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// TODO: access control list check for this user on this dir
		//args.tiltSeriesDir

		// make the job
		val job = TomographyImportDataJob(userId, projectId)
		job.args.next = args
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<TomographyImportDataJob> =
		authJob(this, permission)

	override suspend fun edit(jobId: String, args: TomographyImportDataArgs?): TomographyImportDataData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): TomographyImportDataData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return TomographyImportDataJob.args().toJson()
	}
}
