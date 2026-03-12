package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.WebCacheDir
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import edu.duke.bartesaghi.micromon.nodes.Tomography3DAVGNodeConfig
import edu.duke.bartesaghi.micromon.pyp.cryodrgnKsample
import edu.duke.bartesaghi.micromon.pyp.cryodrgnPc
import edu.duke.bartesaghi.micromon.pyp.cryodrgnEpoch
import edu.duke.bartesaghi.micromon.pyp.cryodrgnSkipumap
import edu.duke.bartesaghi.micromon.pyp.svaClassNum
import edu.duke.bartesaghi.micromon.pyp.svaIterations
import edu.duke.bartesaghi.micromon.pyp.svaCenteringIterations
import edu.duke.bartesaghi.micromon.pyp.svaApplySymmetry
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import java.nio.file.Path
import kotlin.io.path.div


actual class Tomography3DAVGService : ITomography3DAVGService, Service {

	companion object {

		fun init(routing: Routing) {

			routing.route("kv/node/${Tomography3DAVGNodeConfig.ID}/{jobId}") {

				fun PipelineContext<Unit, ApplicationCall>.authJob(permission: ProjectPermission): AuthInfo<Tomography3DAVGJob> {
					val jobId = call.parameters.getOrFail("jobId")
					return call.authJob(jobId, permission)
				}

				fun PipelineContext<Unit,ApplicationCall>.parseClassNum(): Int =
					call.parameters.getOrFail("classNum").toIntOrNull()
						?: throw BadRequestException("classNum must be an integer")

				route("filtered") {

					get("mrc") {
						call.respondExceptions {
							// parse args
							val job = authJob(ProjectPermission.Read).job
							call.respondFileMrc(job.avgDir() / "${job.dir.fileName}_test_metric_filtered.mrc")
						}
					}

				}

				route("itersym/{iter}") {

					fun PipelineContext<Unit,ApplicationCall>.parseIteration(): Int =
						call.parameters.getOrFail("iter").toIntOrNull()
							?: throw BadRequestException("iteration must be an integer")

					get("mrc") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val iteration = parseIteration()

							call.respondFileMrc(job.avgDir() / "${job.dir.fileName}_global_average_${iteration}_symmetrized.mrc")
						}
					}

				}

				get("testmetricfilter/{size}") {
					call.respondExceptions {

						// parse args
						val job = authJob(ProjectPermission.Read).job
						val size = parseSize()

						// serve the image
						val imagePath = job.avgDir() / "${job.dir.fileName}_test_metric_filtered.webp"
						val cacheKey = WebCacheDir.Keys.ThreeDavgImage.parameterized("$job")
						ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
							?.respondPlaceholder(call, size)
					}
				}

				route("iter/{iter}") {

					fun PipelineContext<Unit,ApplicationCall>.parseIteration(): Int =
						call.parameters.getOrFail("iter").toIntOrNull()
							?: throw BadRequestException("iteration must be an integer")

					get("image/{size}") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val size = parseSize()
							val iteration = parseIteration()

							// serve the image
							val imagePath = job.avgDir() / "${job.dir.fileName}_global_average_${iteration}.webp"
							val cacheKey = WebCacheDir.Keys.ThreeDavgImage.parameterized("$iteration")
							ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
								?.respondPlaceholder(call, size)
						}
					}

					get("imagesym/{size}") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val size = parseSize()
							val iteration = parseIteration()

							// serve the image
							val imagePath = job.avgDir() / "${job.dir.fileName}_global_average_${iteration}_symmetrized.webp"
							val cacheKey = WebCacheDir.Keys.ThreeDavgImageSymmetrized.parameterized("sym$iteration")
							ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
								?.respondPlaceholder(call, size)
						}
					}

					get("mrc") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val iteration = parseIteration()

							call.respondFileMrc(job.avgDir() / "${job.dir.fileName}_global_average_${iteration}.mrc")
						}
					}

					get("references/{size}") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val size = parseSize()
							val iteration = parseIteration()

							// serve the image
							val imagePath = job.avgDir() / "${job.dir.fileName}_iteration_${iteration.formatIter()}_refined_selected_average_0.webp"
							val cacheKey = WebCacheDir.Keys.ThreeDavgImage.parameterized("$iteration")
							ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
								?.respondPlaceholder(call, size)
						}
					}

					get("referencesFiltered/{size}") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val size = parseSize()
							val iteration = parseIteration()

							// serve the image
							val imagePath = job.avgDir() / "${job.dir.fileName}_iteration_${iteration.formatIter()}_refined_selected_average_0_filtered.webp"
							val cacheKey = WebCacheDir.Keys.ThreeDavgImageFiltered.parameterized("$iteration")
							ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
								?.respondPlaceholder(call, size)
						}
					}

					get("references/mrc") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val iteration = parseIteration()

							call.respondFileMrc(job.avgDir() / "${job.dir.fileName}_iteration_${iteration.formatIter()}_refined_selected_average_0.mrc")
						}
					}

					get("referencesFiltered") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val iteration = parseIteration()

							call.respondFileMrc(job.avgDir() / "${job.dir.fileName}_iteration_${iteration.formatIter()}_refined_selected_average_0_filtered.mrc")
						}
					}

					route("class/{classNum}") {

						get("image/{size}") {
							call.respondExceptions {

								// parse args
								val job = authJob(ProjectPermission.Read).job
								val classNum = parseClassNum()
								val size = parseSize()
								val iteration = parseIteration()
								val params = job.params()
									?: throw NotFoundException()

								// serve the image
								val imagePath = job.avgDir() / "${job.dir.fileName}_iteration_${iteration.formatIter()}_level_${params.classes}_average_${classNum.formatCls()}.webp"
								val cacheKey = WebCacheDir.Keys.ThreeDavgClass.parameterized("${iteration}_${classNum}")
								ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
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

								val iteration = parseIteration()
								call.respondFileMrc(job.avgDir() / "${job.dir.fileName}_iteration_${iteration.formatIter()}_level_${params.classes}_average_${classNum.formatCls()}.mrc")
							}
						}
					}

					route("refinedclass/{classNum}") {

						get("image/{size}") {
							call.respondExceptions {

								// parse args
								val job = authJob(ProjectPermission.Read).job
								val classNum = parseClassNum()
								val size = parseSize()
								val iteration = parseIteration()
								val params = job.params()
									?: throw NotFoundException()

								// serve the image
								val imagePath = job.avgDir() / "${job.dir.fileName}_iteration_${iteration.formatIter()}_refined_level_${params.classes}_average_${classNum.formatCls()}.webp"
								val cacheKey = WebCacheDir.Keys.ThreeDavgRefinedClass.parameterized("${iteration}_${classNum}")
								ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
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

								val iteration = parseIteration()
								call.respondFileMrc(job.avgDir() / "${job.dir.fileName}_iteration_${iteration.formatIter()}_refined_level_${params.classes}_average_${classNum.formatCls()}.mrc")
							}
						}
					}

				}
			}
		}

		fun Job.avgDir(): Path =
			dir / "3DAVG"

		fun Int.formatIter(): String =
			"%03d".format(this)
		
		fun Int.formatCls(): String =
			"%03d".format(this-1)

	}

	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: Tomography3DAVGArgs): Tomography3DAVGData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the job
		val job = Tomography3DAVGJob(userId, projectId)
		job.args.next = args
		job.movieRefinement = inData
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<Tomography3DAVGJob> =
		authJob(this, permission)

	override suspend fun edit(jobId: String, args: Tomography3DAVGArgs?): Tomography3DAVGData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): Tomography3DAVGData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return Tomography3DAVGJob.args().toJson()
	}

	override suspend fun getParams(jobId: String): Option<Tomography3DAVGParams> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		job.pypParameters()
			?.let {
				Tomography3DAVGParams(
					classes = it.svaClassNum,
					iterations = it.svaIterations,
					centeringIterations = it.svaCenteringIterations,
					applySymmetry = it.svaApplySymmetry
				)
			}
			.toOption()
	}

	override suspend fun classMrcData(jobId: String, iter: Int, classNum: Int): Option<FileDownloadData> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job
		val params = job.params()
			?: throw NotFoundException()

		val path = job.avgDir() / "${job.dir.fileName}_iteration_${iter.formatIter()}_level_${params.classes}_average_${classNum.formatCls()}.mrc"
		return path
			.toFileDownloadData()
			.toOption()
	}

	override suspend fun refinedClassMrcData(jobId: String, iter: Int, classNum: Int): Option<FileDownloadData> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job
		val params = job.params()
			?: throw NotFoundException()

		val path = job.avgDir() / "${job.dir.fileName}_iteration_${iter.formatIter()}_refined_level_${params.classes}_average_${classNum.formatCls()}.mrc"
		return path
			.toFileDownloadData()
			.toOption()
	}

	override suspend fun averageMrcData(jobId: String, iter: Int): Option<FileDownloadData> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job
		val path = job.avgDir() / "${job.dir.fileName}_global_average_${iter}.mrc"
		return path
			.toFileDownloadData()
			.toOption()
	}

	override suspend fun averageSymmetrizedMrcData(jobId: String, iter: Int): Option<FileDownloadData> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job
		val path = job.avgDir() / "${job.dir.fileName}_global_average_${iter}_symmetrized.mrc"
		return path
			.toFileDownloadData()
			.toOption()
	}

	override suspend fun testMetricFilterMrcData(jobId: String): Option<FileDownloadData> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job
		val path = job.avgDir() / "${job.dir.fileName}_test_metric_filtered.mrc"
		return path
			.toFileDownloadData()
			.toOption()
	}

	override suspend fun referencesMrcData(jobId: String, iter: Int): Option<FileDownloadData> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		val path = job.avgDir() / "${job.dir.fileName}_iteration_${iter.formatIter()}_refined_selected_average_0.mrc"
		return path
			.toFileDownloadData()
			.toOption()
	}

	override suspend fun referencesFilteredMrcData(jobId: String, iter: Int): Option<FileDownloadData> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		val path = job.avgDir() / "${job.dir.fileName}_iteration_${iter.formatIter()}_refined_selected_average_0_filtered.mrc"
		return path
			.toFileDownloadData()
			.toOption()
	}

}
