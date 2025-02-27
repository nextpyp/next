package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.WebCacheDir
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import edu.duke.bartesaghi.micromon.nodes.TomographyDrgnEvalNodeConfig
import edu.duke.bartesaghi.micromon.pyp.tomodrgnAnalyzeKsample
import edu.duke.bartesaghi.micromon.pyp.tomodrgnAnalyzePc
import edu.duke.bartesaghi.micromon.pyp.tomodrgnAnalyzeSkipumap
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import java.nio.file.Path
import kotlin.io.path.div


actual class TomographyDrgnEvalService : ITomographyDrgnEvalService, Service {

	companion object {

		fun init(routing: Routing) {

			routing.route("kv/node/${TomographyDrgnEvalNodeConfig.ID}/{jobId}") {

				fun PipelineContext<Unit, ApplicationCall>.authJob(permission: ProjectPermission): AuthInfo<Job> {
					val jobId = call.parameters.getOrFail("jobId")
					return call.authJob(jobId, permission)
				}

				fun PipelineContext<Unit,ApplicationCall>.parseClassNum(): Int =
					call.parameters.getOrFail("classNum").toIntOrNull()
						?: throw BadRequestException("classNum must be an integer")

				route("umap") {

					route("class/{classNum}") {

						get("image/{size}") {
							call.respondExceptions {

								// parse args
								val job = authJob(ProjectPermission.Read).job
								val classNum = parseClassNum()
								val size = parseSize()

								// serve the image
								val imagePath = job.kmeansDir() / "vol_${classNum.formatCls()}.webp"
								val cacheKey = WebCacheDir.Keys.tomoDrgnVolume.parameterized("$classNum")
								ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
									?.respondPlaceholder(call, size)
							}
						}

						get("mrc") {
							call.respondExceptions {

								// parse args
								val job = authJob(ProjectPermission.Read).job
								val classNum = parseClassNum()

								call.respondFileMrc(job.kmeansDir() / "vol_${classNum.formatCls()}.mrc")
							}
						}
					}

					get("plot/resolution") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job

							// serve the image
							ImageType.Svgz.respond(call, job.kmeansDir() / "z_umap_scatter_subplotkmeanslabel.svgz")
								?.respondPlaceholder(call)
						}
					}

					get("plot/occupancy") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job

							// serve the image
							ImageType.Svgz.respond(call, job.kmeansDir() / "z_umap_scatter_colorkmeanslabel.svgz")
								?.respondPlaceholder(call)
						}
					}
				}

				route("pca") {

					route("dim/{dim}/class/{classNum}") {

						fun PipelineContext<Unit,ApplicationCall>.parseDim(): Int =
							call.parameters.getOrFail("dim").toIntOrNull()
								?: throw BadRequestException("dim must be an integer")

						get("image/{size}") {
							call.respondExceptions {

								// parse args
								val job = authJob(ProjectPermission.Read).job
								val dim = parseDim()
								val classNum = parseClassNum()
								val size = parseSize()

								// serve the image
								val imagePath = job.dimDir(dim) / "vol_${classNum.formatCls()}.webp"
								val cacheKey = WebCacheDir.Keys.tomoDrgnVolume.parameterized("$classNum")
								ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
									?.respondPlaceholder(call, size)
							}
						}

						get("mrc") {
							call.respondExceptions {

								// parse args
								val job = authJob(ProjectPermission.Read).job
								val dim = parseDim()
								val classNum = parseClassNum()

								call.respondFileMrc(job.dimDir(dim) / "vol_${classNum.formatCls()}.mrc")
							}
						}
					}

					get("plot/resolution") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job

							// serve the image
							ImageType.Svgz.respond(call, job.kmeansDir() / "z_pca_scatter_subplotkmeanslabel.svgz")
								?.respondPlaceholder(call)
						}
					}

					get("plot/occupancy") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job

							// serve the image
							ImageType.Svgz.respond(call, job.kmeansDir() / "z_pca_scatter_colorkmeanslabel.svgz")
								?.respondPlaceholder(call)
						}
					}
				}
			}
		}

		fun Job.kmeansDir(): Path =
			dir / "train" / "kmeans"

		fun Job.dimDir(dim: Int): Path =
			kmeansDir() / "dims.$dim"

		fun Int.formatCls(): String =
			"%03d".format(this)
	}


	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyDrgnEvalArgs): TomographyDrgnEvalData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the job
		val job = TomographyDrgnEvalJob(userId, projectId)
		job.args.next = args
		job.inModel = inData
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<TomographyDrgnEvalJob> =
		authJob(this, permission)

	override suspend fun edit(jobId: String, args: TomographyDrgnEvalArgs?): TomographyDrgnEvalData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): TomographyDrgnEvalData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return TomographyDrgnEvalJob.args().toJson()
	}

	override suspend fun getParams(jobId: String): Option<TomographyDrgnEvalParams> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		job.pypParameters()
			?.let {
				TomographyDrgnEvalParams(
					skipumap = it.tomodrgnAnalyzeSkipumap,
					pc = it.tomodrgnAnalyzePc,
					ksample = it.tomodrgnAnalyzeKsample
				)
			}
			.toOption()
	}

	override suspend fun classMrcDataUmap(jobId: String, classNum: Int): Option<FileDownloadData> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		val path = job.kmeansDir() / "vol_${classNum.formatCls()}.mrc"
		return path
			.toFileDownloadData()
			.toOption()
	}

	override suspend fun classMrcDataPca(jobId: String, dim: Int, classNum: Int): Option<FileDownloadData> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		val path = job.dimDir(dim) / "vol_${classNum.formatCls()}.mrc"
		return path
			.toFileDownloadData()
			.toOption()
	}
}
