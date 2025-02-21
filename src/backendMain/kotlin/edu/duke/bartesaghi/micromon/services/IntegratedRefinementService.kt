package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.jobs.AuthInfo
import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.jobs.authJob
import edu.duke.bartesaghi.micromon.linux.userprocessor.WebCacheDir
import edu.duke.bartesaghi.micromon.pyp.Reconstruction
import edu.duke.bartesaghi.micromon.pyp.Refinement
import edu.duke.bartesaghi.micromon.pyp.RefinementBundle
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.fileSize


actual class IntegratedRefinementService : IIntegratedRefinementService, Service {

	companion object {

		private val Job.mapsDir: Path get() =
			dir / "frealign" / "maps"

		fun mapImageUrl(jobId: String, classNum: Int, iteration: Int, size: ImageSize): String =
			"kv/reconstructions/$jobId/$classNum/$iteration/images/map/${size.id}"

		fun init(routing: Routing) {

			fun PipelineContext<Unit, ApplicationCall>.authJob(permission: ProjectPermission): AuthInfo<Job> {
				val jobId = call.parameters.getOrFail("jobId")
				return call.authJob(jobId, permission)
			}

			routing.route("kv/reconstructions/{jobId}/{class}/{iteration}") {

				fun PipelineContext<Unit,ApplicationCall>.parseClass(): Int =
					call.parameters.getOrFail("class")
						.toIntOrNull()
						?: throw BadRequestException("class must be an integer")

				fun PipelineContext<Unit,ApplicationCall>.parseIteration(): Int =
					call.parameters.getOrFail("iteration")
						.toIntOrNull()
						?: throw BadRequestException("iteration must be an integer")

				get("map/{type}") {
					call.respondExceptions {

						// parse args
						val job = authJob(ProjectPermission.Read).job
						val classNum = parseClass()
						val iteration = parseIteration()

						val type = call.parameters.getOrFail("type")
							.let { MRCType[it] }
							?: throw BadRequestException("unrecognized map type")

						// get the path to the MRC file
						val fragment = Reconstruction.filenameFragment(job, classNum, iteration.takeIf { type.usesIteration })
						val suffix = type.filenameFragment
							?.let { "_$it" }
							?: ""
						val filename = "$fragment$suffix.mrc"
						val path = job.mapsDir / filename

						call.respondFileMrc(path, filename)
					}
				}

				// parse args
				get("plots") {
					call.respondExceptions {

						// parse args
						val job = authJob(ProjectPermission.Read).job
						val classNum = parseClass()
						val iteration = parseIteration()

						val path = job.mapsDir / "${Reconstruction.filenameFragment(job, classNum, iteration)}_plots.json"
						call.respondFile(path, ContentType.Application.Json)
					}
				}

				get("bild") {
					call.respondExceptions {

						// parse args
						val job = authJob(ProjectPermission.Read).job
						val classNum = parseClass()
						val iteration = parseIteration()

						val path = job.mapsDir / "${Reconstruction.filenameFragment(job, classNum, iteration)}.bild"
						call.respondFile(path, ContentType.Application.OctetStream)
					}
				}

				route("images") {

					get("map/{size}") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val classNum = parseClass()
							val iteration = parseIteration()
							val size = parseSize()

							// serve the image
							val fragment = Reconstruction.filenameFragment(job, classNum, iteration)
							val imagePath = job.mapsDir / "${fragment}_map.webp"
							val cacheKey = WebCacheDir.Keys.map.parameterized(fragment)
							ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
								?.respondPlaceholder(call, size)
						}
					}

					get("fyp/{size}") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val classNum = parseClass()
							val iteration = parseIteration()
							val size = parseSize()

							// serve the image
							val fragment = Reconstruction.filenameFragment(job, classNum, iteration)
							val imagePath = job.mapsDir / "${fragment}_fyp.png"
							val cacheKey = WebCacheDir.Keys.fyp.parameterized(fragment)
							ImageType.Png.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
								?.respondPlaceholder(call, size)
						}
					}

					get("perParticleScores") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val classNum = parseClass()
							val iteration = parseIteration()

							// serve the image
							val fragment = Reconstruction.filenameFragment(job, classNum, iteration)
							val imagePath = job.mapsDir / "${fragment}_scores.svgz"
							ImageType.Svgz.respond(call, imagePath)
								?.respondPlaceholder(call)
						}
					}
				}
			}

			routing.route("kv/refinements/{jobId}") {

				route("refinement/{dataId}") {

					fun PipelineContext<Unit,ApplicationCall>.parseDataId(): String =
						call.parameters.getOrFail("dataId")

					get("particles/{size}") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val dataId = parseDataId()
							val size = parseSize()

							// serve the image
							val imagePath = job.dir / "csp" / "${dataId}_local.webp"
							val cacheKey = WebCacheDir.Keys.particles.parameterized(dataId)
							ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
								?.respondPlaceholder(call, size)
						}
					}

					get("scores") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val dataId = parseDataId()

							// serve the image
							val imagePath = job.dir / "csp" / "${dataId}_scores.svgz"
							ImageType.Svgz.respond(call, imagePath)
								?.respondPlaceholder(call)
						}
					}
				}

				route("bundle/{bundleId}") {

					fun PipelineContext<Unit,ApplicationCall>.parseBundleId(): String =
						call.parameters.getOrFail("bundleId")

					get("scores") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val bundleId = parseBundleId()

							// serve the image
							val imagePath = job.dir / "frealign" / "${bundleId}_scores.svgz"
							ImageType.Svgz.respond(call, imagePath)
								?.respondPlaceholder(call)
						}
					}

					get("weights") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val bundleId = parseBundleId()

							// serve the image
							val imagePath = job.dir / "frealign" / "${bundleId}_weights.svgz"
							ImageType.Svgz.respond(call, imagePath)
								?.respondPlaceholder(call)
						}
					}
				}
			}
		}
	}

    @Inject
    override lateinit var call: ApplicationCall

	private fun String.authJob(permission: ProjectPermission): AuthInfo<Job> =
		authJob(this, permission)

	private fun getReconstructionOrThrow(jobId: String, reconstructionId: String): Reconstruction =
        Reconstruction.get(jobId, reconstructionId)
            ?: throw NoSuchElementException("no reconstruction with id $reconstructionId found in job $jobId")

    override suspend fun getReconstruction(jobId: String, reconstructionId: String): ReconstructionData = sanitizeExceptions {
        jobId.authJob(ProjectPermission.Read)
        return getReconstructionOrThrow(jobId, reconstructionId).toData()
    }

    override suspend fun getReconstructions(jobId: String): List<ReconstructionData> = sanitizeExceptions {
		jobId.authJob(ProjectPermission.Read)
        return Reconstruction.getAll(jobId) { cursor ->
            cursor
                .map { it.toData() }
                .toList()
        }
    }

    override suspend fun getReconstructionPlotsData(jobId: String, reconstructionId: String): ReconstructionPlotsData {
		jobId.authJob(ProjectPermission.Read)
        return getReconstructionOrThrow(jobId, reconstructionId)
			.toPlotsData()
    }

    override suspend fun getLog(jobId: String, reconstructionId: String): String = sanitizeExceptions {
		val job = jobId.authJob(ProjectPermission.Read).job
        val reconstruction = getReconstructionOrThrow(jobId, reconstructionId)
        return reconstruction.getLog(job)
    }

	override suspend fun getRefinements(jobId: String): List<RefinementData> = sanitizeExceptions {

		jobId.authJob(ProjectPermission.Read)

		return Refinement.getAll(jobId)
			.map { it.toData() }
	}

	override suspend fun getRefinementBundles(jobId: String): List<RefinementBundleData> = sanitizeExceptions {

		jobId.authJob(ProjectPermission.Read)

		return RefinementBundle.getAll(jobId)
			.map { it.toData() }
	}

	override suspend fun getBildData(jobId: String, reconstructionId: String): Option<FileDownloadData> = sanitizeExceptions {
		val job = jobId.authJob(ProjectPermission.Read).job
		val reconstruction = getReconstructionOrThrow(jobId, reconstructionId)
		val filename = "${Reconstruction.filenameFragment(job, reconstruction.classNum, reconstruction.iteration)}.bild"
		return (job.mapsDir / filename)
			.toFileDownloadData()
			.toOption()
	}
}
