package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.jobs.AuthInfo
import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.jobs.authJob
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
import java.io.FileNotFoundException
import java.nio.file.Path
import kotlin.io.path.div


actual class IntegratedRefinementService : IIntegratedRefinementService, Service {

	companion object {

		fun mapImageUrl(jobId: String, classNum: Int, iteration: Int, size: ImageSize): String =
			"kv/reconstructions/$jobId/$classNum/$iteration/images/map/${size.id}"

		fun init(routing: Routing) {

			fun PipelineContext<Unit,ApplicationCall>.parseJobId(): String =
				call.parameters.getOrFail("JobId")

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
						val jobId = parseJobId()
						val classNum = parseClass()
						val iteration = parseIteration()

						val type = call.parameters.getOrFail("type")
							.let { MRCType[it] }
							?: throw BadRequestException("unrecognized map type")

						val (filename, bytes) = service.getMrcFile(jobId, classNum, iteration, type)

						// send the content length using a custom header, so we can show a progress bar
						call.response.header("MRC-FILE-SIZE", bytes.size.toString())

						// and send a suggested filename for the browser download too
						call.response.header("Content-Disposition", "attachment; filename=\"$filename\"")

						call.respondBytes(bytes, ContentType.Application.OctetStream)
					}
				}

				// parse args
				get("plots") {
					call.respondExceptions {

						// parse args
						val jobId = parseJobId()
						val classNum = parseClass()
						val iteration = parseIteration()

						val bytes = service.getPlotData(jobId, classNum, iteration)

						// TODO: can the client side read this? or is it expecting a binary payload?
						call.respondBytes(bytes, ContentType.Application.Json)
					}
				}

				route("images") {

					get("map/{size}") {
						call.respondExceptions {

							// parse args
							val jobId = parseJobId()
							val classNum = parseClass()
							val iteration = parseIteration()
							val size = parseSize()

							val bytes = service.getMapImage(jobId, classNum, iteration, size)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}

					get("fyp/{size}") {
						call.respondExceptions {

							// parse args
							val jobId = parseJobId()
							val classNum = parseClass()
							val iteration = parseIteration()
							val size = parseSize()

							val bytes = service.getFypImage(jobId, classNum, iteration, size)

							call.respondBytes(bytes, ContentType.Image.PNG)
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
							val jobId = parseJobId()
							val dataId = parseDataId()
							val size = parseSize()

							val bytes = service.getParticlesImage(jobId, dataId, size)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}

					get("scores") {
						call.respondExceptions {

							// parse args
							val jobId = parseJobId()
							val dataId = parseDataId()

							val bytes = service.getScoresImage(jobId, dataId)

							call.response.headers.append(HttpHeaders.ContentEncoding, "gzip")
							call.respondBytes(bytes, ContentType.Image.Svgz)
						}
					}
				}

				route("bundle/{bundleId}") {

					fun PipelineContext<Unit,ApplicationCall>.parseBundleId(): String =
						call.parameters.getOrFail("bundleId")

					get("scores") {
						call.respondExceptions {

							// parse args
							val jobId = parseJobId()
							val bundleId = parseBundleId()

							val bytes = service.getBundleScoresImage(jobId, bundleId)

							call.response.headers.append(HttpHeaders.ContentEncoding, "gzip")
							call.respondBytes(bytes, ContentType.Image.Svgz)
						}
					}

					get("weights") {
						call.respondExceptions {

							// parse args
							val jobId = parseJobId()
							val bundleId = parseBundleId()

							val bytes = service.getBundleWeightsImage(jobId, bundleId)

							call.response.headers.append(HttpHeaders.ContentEncoding, "gzip")
							call.respondBytes(bytes, ContentType.Image.Svgz)
						}
					}
				}
			}
		}

		private val PipelineContext<Unit,ApplicationCall>.service get() =
			getService<IntegratedRefinementService>()
	}

    @Inject
    override lateinit var call: ApplicationCall

	private fun String.authJob(permission: ProjectPermission): AuthInfo<Job> =
		authJob(permission, this)

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

	private val Job.mapsDir: Path get() =
		dir / "frealign" / "maps"

	fun getFypImage(jobId: String, classNum: Int, iteration: Int, size: ImageSize): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job
		val path = job.mapsDir / "${Reconstruction.filenameFragment(job, classNum, iteration)}_fyp.png"
		val cacheInfo = ImageCacheInfo(job.wwwDir, "fyp-${Reconstruction.filenameFragment(job, classNum, iteration)}")
		return size.readResize(path, ImageType.Png, cacheInfo)
			?: throw FileNotFoundException(path.toString())
	}

	fun getMapImage(jobId: String, classNum: Int, iteration: Int, size: ImageSize): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job
		val path = job.mapsDir / "${Reconstruction.filenameFragment(job, classNum, iteration)}_map.webp"
		val cacheInfo = ImageCacheInfo(job.wwwDir, "map-${Reconstruction.filenameFragment(job, classNum, iteration)}")
		return size.readResize(path, ImageType.Webp, cacheInfo)
			?: throw FileNotFoundException(path.toString())
	}

	fun getPlotData(jobId: String, classNum: Int, iteration: Int): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job
		val path = job.mapsDir / "${Reconstruction.filenameFragment(job, classNum, iteration)}_plots.json"
		return path.readBytes()
	}

	fun getMrcFile(jobId: String, classNum: Int, iteration: Int, type: MRCType): Pair<String,ByteArray> {
		val job = jobId.authJob(ProjectPermission.Read).job
		val suffix = type.filenameFragment
			?.let { "_$it" }
			?: ""
		val filename = "${Reconstruction.filenameFragment(job, classNum, iteration.takeIf { type.usesIteration })}$suffix.mrc"
		val path = job.mapsDir / filename
		return filename to path.readBytes()
	}

	fun getParticlesImage(jobId: String, dataId: String, size: ImageSize): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job
		val path = job.dir / "csp" / "${dataId}_local.webp"
		val cacheInfo = ImageCacheInfo(job.wwwDir, "particles-$dataId")
		return size.readResize(path, ImageType.Webp, cacheInfo)
			?: throw FileNotFoundException(path.toString())
	}

	fun getScoresImage(jobId: String, dataId: String): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job
		val path = job.dir / "csp" / "${dataId}_scores.svgz"
		return path.readBytes()
	}

	fun getBundleScoresImage(jobId: String, bundleId: String): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job
		val path = job.dir / "frealign" / "${bundleId}_scores.svgz"
		return path.readBytes()
	}

	fun getBundleWeightsImage(jobId: String, bundleId: String): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job
		val path = job.dir / "frealign" / "${bundleId}_weights.svgz"
		return path.readBytes()
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
}
