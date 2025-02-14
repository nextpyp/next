package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.WebCacheDir
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import edu.duke.bartesaghi.micromon.nodes.TomographyMiloEvalNodeConfig
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlin.io.path.div


actual class TomographyMiloEvalService : ITomographyMiloEvalService, Service {

	companion object {

		fun init(routing: Routing) {

			routing.route("kv/node/${TomographyMiloEvalNodeConfig.ID}/{jobId}") {

				fun PipelineContext<Unit, ApplicationCall>.authJob(permission: ProjectPermission): AuthInfo<TomographyMiloEvalJob> {
					val jobId = call.parameters.getOrFail("jobId")
					return call.authJob(jobId, permission)
				}

				get("results_2d/{size}") {
					call.respondExceptions {

						// parse args
						val job = authJob(ProjectPermission.Read).job
						val size = parseSize()

						// serve the image
						val imagePath = job.dir / "train" / "2d_visualization_out.webp"
						val cacheKey = WebCacheDir.Keys.miloResults2D
						ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
							?.respondPlaceholder(call, size)
					}
				}

				get("results_2d_labels/{size}") {
					call.respondExceptions {

						// parse args
						val job = authJob(ProjectPermission.Read).job
						val size = parseSize()

						// serve the image
						val imagePath = job.dir / "train" / "2d_visualization_labels.webp"
						val cacheKey = WebCacheDir.Keys.miloLabels2D
						ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
							?.respondPlaceholder(call, size)
					}
				}

				get("results_3d/{size}") {
					call.respondExceptions {

						// parse args
						val job = authJob(ProjectPermission.Read).job
						val size = parseSize()

						// serve the image
						val imagePath = job.dir / "train" / "3d_visualization_out.webp"
						val cacheKey = WebCacheDir.Keys.miloResults3D
						ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
							?.respondPlaceholder(call, size)
					}
				}

				get("data") {
					call.respondExceptions {

						// parse args
						val job = authJob(ProjectPermission.Read).job

						val path = job.dir / "train" / "interactive_info_parquet.gzip"
						call.respondFile(path, ContentType.Application.OctetStream)
					}
				}

				route("tilt_series/{tiltSeriesId}") {

					fun PipelineContext<Unit, ApplicationCall>.parseTiltSeriesId(): String =
						call.parameters.getOrFail("tiltSeriesId")

					get("results_3d/{size}") {
						call.respondExceptions {

							// parse agrs
							val job = authJob(ProjectPermission.Read).job
							val tiltSeriesId = parseTiltSeriesId()
							val size = parseSize()

							//"train/{}_3d_visualization.webp"

							// serve the image
							val imagePath = job.dir / "train" / "${tiltSeriesId}_3d_visualization.webp"
							val cacheKey = WebCacheDir.Keys.miloResults3D.parameterized(tiltSeriesId)
							ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
								?.respondPlaceholder(call, size)
						}
					}
				}

				route("upload_particles", FileUpload.routeHandler { permission ->
					val job = authJob(permission).job
					val project = job.projectOrThrow()
					FileUpload(job.dir / "particles.parquet", project.osUsername)
				})
			}
		}
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
		authJob(this, permission)

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

	override suspend fun data(jobId: String): Option<FileDownloadData> = sanitizeExceptions {
		val job = jobId.authJob(ProjectPermission.Read).job
		val path = job.dir / "train" / "interactive_info_parquet.gzip"
		path
			.toFileDownloadData()
			.toOption()
	}
}
