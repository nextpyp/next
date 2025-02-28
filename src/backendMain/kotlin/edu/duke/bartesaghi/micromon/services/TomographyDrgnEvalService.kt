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

				fun PipelineContext<Unit, ApplicationCall>.authJob(permission: ProjectPermission): AuthInfo<TomographyDrgnEvalJob> {
					val jobId = call.parameters.getOrFail("jobId")
					return call.authJob(jobId, permission)
				}

				fun PipelineContext<Unit,ApplicationCall>.parseClassNum(): Int =
					call.parameters.getOrFail("classNum").toIntOrNull()
						?: throw BadRequestException("classNum must be an integer")

				route("plot") {

					fun makePlotHandler(key: String, basenamer: (String) -> String) {
						get(key) {
							call.respondExceptions {

								// parse args
								val job = authJob(ProjectPermission.Read).job

								val imageType = ImageType.Svgz
								val params = job.params()
									?: return@respondExceptions imageType.respondPlaceholder(call)

								// serve the image
								imageType.respond(call, job.kmeansDir(params) / "${basenamer(key)}.svgz")
									?.respondPlaceholder(call)
							}
						}
					}

					makePlotHandler("umap_scatter_subplotkmeanslabel") { "z_$it" }
					makePlotHandler("umap_scatter_colorkmeanslabel") { "z_$it" }
					makePlotHandler("umap_scatter_annotatekmeans") { "z_$it" }
					makePlotHandler("umap_hexbin_annotatekmeans") { "z_$it" }
					makePlotHandler("pca_scatter_subplotkmeanslabel") { "z_$it" }
					makePlotHandler("pca_scatter_colorkmeanslabel") { "z_$it" }
					makePlotHandler("pca_scatter_annotatekmeans") { "z_$it" }
					makePlotHandler("pca_hexbin_annotatekmeans") { "z_$it" }
					makePlotHandler("tomogram_label_distribution") { "tomogram_label_distribution" }

					fun makePlotHandlerDim(key: String, dim: Int, basenamer: (String) -> String) {
						get(key) {
							call.respondExceptions {

								// parse args
								val job = authJob(ProjectPermission.Read).job

								val imageType = ImageType.Svgz
								val params = job.params()
									?: return@respondExceptions imageType.respondPlaceholder(call)

								// serve the image
								imageType.respond(call, job.dimDir(dim) / "${basenamer(key)}.svgz")
									?.respondPlaceholder(call)
							}
						}
					}

					makePlotHandlerDim("umap_hexbin_annotatepca", 1) { "z_$it" }
					makePlotHandlerDim("umap_scatter_annotatepca", 1) { "z_$it" }
					makePlotHandlerDim("pca_hexbin_annotatepca", 1) { "z_$it" }
					makePlotHandlerDim("pca_scatter_annotatepca", 1) { "z_$it" }
				}

				route("class/{classNum}") {

					get("image/{size}") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val classNum = parseClassNum()
							val size = parseSize()

							val imageType = ImageType.Webp
							val params = job.params()
								?: return@respondExceptions imageType.respondPlaceholder(call, size)

							// serve the image
							val imagePath = job.kmeansDir(params) / "vol_${classNum.formatCls()}.webp"
							val cacheKey = WebCacheDir.Keys.tomoDrgnVolume.parameterized("$classNum")
							imageType.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
								?.respondPlaceholder(call, size)
						}
					}

					get("mrc") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val classNum = parseClassNum()

							val params = job.params()
								?: throw NotFoundException()

							call.respondFileMrc(job.kmeansDir(params) / "vol_${classNum.formatCls()}.mrc")
						}
					}
				}

				route("dim/{dim}") {

					fun PipelineContext<Unit,ApplicationCall>.parseDim(): Int =
						call.parameters.getOrFail("dim").toIntOrNull()
							?: throw BadRequestException("dim must be an integer")

					route("plot") {

						fun makePlotHandler(key: String, basenamer: (String) -> String) {
							get(key) {
								call.respondExceptions {

									// parse args
									val job = authJob(ProjectPermission.Read).job
									val dim = parseDim()

									// serve the image
									ImageType.Svgz.respond(call, job.dimDir(dim) / "${basenamer(key)}.svgz")
										?.respondPlaceholder(call)
								}
							}
						}

						makePlotHandler("umap_colorlatentpca") { "z_$it" }
					}

					route("class/{classNum}") {

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
				}
			}
		}

		fun Job.kmeansDir(params: TomographyDrgnEvalParams): Path =
			dir / "train" / "kmeans${params.ksample}"

		fun Job.dimDir(dim: Int): Path =
			dir / "train" / "pc$dim"

		fun Int.formatCls(): String =
			"%03d".format(this - 1)
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

		val params = job.params()
			?: return null.toOption()

		val path = job.kmeansDir(params) / "vol_${classNum.formatCls()}.mrc"
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
