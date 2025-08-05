package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.WebCacheDir
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import edu.duke.bartesaghi.micromon.nodes.SingleParticleDrgnNodeConfig
import edu.duke.bartesaghi.micromon.pyp.cryodrgnKsample
import edu.duke.bartesaghi.micromon.pyp.cryodrgnPc
import edu.duke.bartesaghi.micromon.pyp.cryodrgnEpoch
import edu.duke.bartesaghi.micromon.pyp.cryodrgnSkipumap
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import java.nio.file.Path
import kotlin.io.path.div


actual class SingleParticleDrgnService : ISingleParticleDrgnService, Service {

	companion object {

		fun init(routing: Routing) {

			routing.route("kv/node/${SingleParticleDrgnNodeConfig.ID}/{jobId}") {

				fun PipelineContext<Unit, ApplicationCall>.authJob(permission: ProjectPermission): AuthInfo<SingleParticleDrgnJob> {
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

					makePlotHandler("umap_hex") { "umap_hex" }
					makePlotHandler("umap") { "umap" }
					makePlotHandler("z_pca_hex") { "z_pca_hex" }
					makePlotHandler("z_pca") { "z_pca" }
					
					fun makePlotHandlerDim(key: String, dim: Int, basenamer: (String) -> String) {
						get(key) {
							call.respondExceptions {

								// parse args
								val job = authJob(ProjectPermission.Read).job

								val imageType = ImageType.Svgz
								val params = job.params()
									?: return@respondExceptions imageType.respondPlaceholder(call)

								// serve the image
								imageType.respond(call, job.dimDir(dim, params) / "${basenamer(key)}.svgz")
									?.respondPlaceholder(call)
							}
						}
					}

					makePlotHandlerDim("pca_traversal_hex", 1) { "pca_traversal_hex" }
					makePlotHandlerDim("pca_traversal", 1) { "pca_traversal" }
					makePlotHandlerDim("umap_traversal", 1) { "umap" }
					makePlotHandlerDim("umap_traversal_connected", 1) { "umap_traversal_connected" }
					makePlotHandlerDim("umap", 1) { "umap" }
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
									val params = job.params()
										?: throw NotFoundException()

									// serve the image
									ImageType.Svgz.respond(call, job.dimDir(dim, params) / "${basenamer(key)}.svgz")
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
								val params = job.params()
									?: throw NotFoundException()

								// serve the image
								val imagePath = job.dimDir(dim, params) / "vol_${classNum.formatCls()}.webp"
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
								val params = job.params()
									?: throw NotFoundException()

								call.respondFileMrc(job.dimDir(dim, params) / "vol_${classNum.formatCls()}.mrc")
							}
						}
					}
				}
			}
		}

		fun Job.kmeansDir(params: SingleParticleDrgnParams): Path =
			dir / "train" / "analyze.${params.epoch-1}" / "kmeans${params.ksample}"

		fun Job.dimDir(dim: Int, params: SingleParticleDrgnParams): Path =
			dir / "train" / "analyze.${params.epoch-1}" / "pc$dim"

		fun Int.formatCls(): String =
			"%03d".format(this - 1)
	}

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
		authJob(this, permission)

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

	override suspend fun getArgs(): String = sanitizeExceptions {
		return SingleParticleDrgnJob.args().toJson()
	}

	override suspend fun getParams(jobId: String): Option<SingleParticleDrgnParams> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		job.pypParameters()
			?.let {
				SingleParticleDrgnParams(
					skipumap = it.cryodrgnSkipumap,
					pc = it.cryodrgnPc,
					ksample = it.cryodrgnKsample,
					epoch = it.cryodrgnEpoch
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
		val params = job.params()
			?: throw NotFoundException()

		val path = job.dimDir(dim, params) / "vol_${classNum.formatCls()}.mrc"
		return path
			.toFileDownloadData()
			.toOption()
	}

}
