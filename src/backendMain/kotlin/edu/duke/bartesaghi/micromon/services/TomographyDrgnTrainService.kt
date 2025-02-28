package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.WebCacheDir
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import edu.duke.bartesaghi.micromon.nodes.TomographyDrgnTrainNodeConfig
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import java.nio.file.Path
import kotlin.io.path.div


actual class TomographyDrgnTrainService : ITomographyDrgnTrainService, Service {

	companion object {

		fun init(routing: Routing) {

			routing.route("kv/node/${TomographyDrgnTrainNodeConfig.ID}/{jobId}") {

				fun PipelineContext<Unit, ApplicationCall>.authJob(permission: ProjectPermission): AuthInfo<Job> {
					val jobId = call.parameters.getOrFail("jobId")
					return call.authJob(jobId, permission)
				}

				get("plot/{number}") {
					call.respondExceptions {

						// parse args
						val job = authJob(ProjectPermission.Read).job
						val number = call.parameters.getOrFail("number").toIntOrNull()
							?: throw BadRequestException("bad number")

						// serve the image
						val dir = job.dir / "train" / "convergence" / "plots"
						when (number) {

							0 -> ImageType.Svgz.respond(call, dir / "00_total_loss.svgz")
								?.respondPlaceholder(call)

							1 -> ImageType.Svgz.respond(call, dir / "01_encoder_pcs.svgz")
								?.respondPlaceholder(call)

							2 -> ImageType.Svgz.respond(call, dir / "02_encoder_umaps.svgz")
								?.respondPlaceholder(call)

							3 -> ImageType.Svgz.respond(call, dir / "03_encoder_latent_vector_shifts.svgz")
								?.respondPlaceholder(call)

							4 -> ImageType.Svgz.respond(call, dir / "04_decoder_UMAP-sketching.svgz")
								?.respondPlaceholder(call)

							5 -> ImageType.Svgz.respond(call, dir / "05_decoder_maxima-sketch-consistency.svgz")
								?.respondPlaceholder(call)

							6 -> ImageType.Svgz.respond(call, dir / "06_decoder_CC.svgz")
								?.respondPlaceholder(call)

							7 -> ImageType.Svgz.respond(call, dir / "07_decoder_FSC.svgz")
								?.respondPlaceholder(call)

							8 -> ImageType.Svgz.respond(call, dir / "08_decoder_FSC-nyquist.svgz")
								?.respondPlaceholder(call)

							else -> throw NotFoundException()
						}
					}
				}

				get("distribution") {
					call.respondExceptions {

						// parse args
						val job = authJob(ProjectPermission.Read).job

						val dir = job.dir / "train"

						// serve the image
						ImageType.Svgz.respond(call, dir / "${job.dir.fileName}_particles.star_particle_uid_ntilt_distribution.svgz")
							?.respondPlaceholder(call)
					}
				}

				route("epoch/{epoch}") {

					fun PipelineContext<Unit,ApplicationCall>.parseEpoch(): Int =
						call.parameters.getOrFail("epoch").toIntOrNull()
							?: throw BadRequestException("epoch must be an integer")

					get("pairwiseCCMatrix") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val epoch = parseEpoch()

							val dir = job.dir / "train" / "convergence" / "plots"

							// serve the image
							ImageType.Svgz.respond(call, dir / "09_pairwise_CC_matrix_epoch-$epoch.svgz")
								?.respondPlaceholder(call)
						}
					}

					route("class/{classNum}") {

						fun PipelineContext<Unit,ApplicationCall>.parseClassNum(): Int =
							call.parameters.getOrFail("classNum").toIntOrNull()
								?: throw BadRequestException("classNum must be an integer")

						get("image/{size}") {
							call.respondExceptions {

								// parse args
								val job = authJob(ProjectPermission.Read).job
								val epoch = parseEpoch()
								val classNum = parseClassNum()
								val size = parseSize()

								// serve the image
								val imagePath = job.epochDir(epoch) / "vol_${(classNum-1).formatCls()}.webp"
								val cacheKey = WebCacheDir.Keys.tomoDrgnVolume.parameterized("$epoch-$classNum")
								ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
									?.respondPlaceholder(call, size)
							}
						}

						get("mrc") {
							call.respondExceptions {

								// parse args
								val job = authJob(ProjectPermission.Read).job
								val epoch = parseEpoch()
								val classNum = parseClassNum()

								call.respondFileMrc(job.epochDir(epoch) / "vol_${(classNum-1).formatCls()}.mrc")
							}
						}
					}
				}
			}
		}

		fun Job.epochDir(epoch: Int): Path =
			dir / "train" / "convergence" / "vols.$epoch"

		fun Int.formatCls(): String =
			"%03d".format(this - 1)
	}


	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyDrgnTrainArgs): TomographyDrgnTrainData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the job
		val job = TomographyDrgnTrainJob(userId, projectId)
		job.args.next = args
		job.inMovieRefinements = inData
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<TomographyDrgnTrainJob> =
		authJob(this, permission)

	override suspend fun edit(jobId: String, args: TomographyDrgnTrainArgs?): TomographyDrgnTrainData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): TomographyDrgnTrainData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return TomographyDrgnTrainJob.args().toJson()
	}

	override suspend fun getConvergence(jobId: String): Option<TomoDrgnConvergence> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		job.convergenceParameters()
			?.let { job.convergence(it) }
			.toOption()
	}

	override suspend fun classMrcData(jobId: String, epoch: Int, classNum: Int): Option<FileDownloadData> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		val path = job.epochDir(epoch) / "vol_${(classNum-1).formatCls()}.mrc"
		return path
			.toFileDownloadData()
			.toOption()
	}
}
