
package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.*
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


actual class JobsService : IJobsService, Service {

	companion object {

		fun dataImageUrl(jobId: String, dataId: String, imageSize: ImageSize): String =
			"/kv/jobs/$jobId/data/$dataId/image/${imageSize.id}"

		fun gainCorrectedImageUrl(jobId: String, imageSize: ImageSize): String =
			"/kv/jobs/$jobId/gainCorrectedImage/${imageSize.id}"

		fun init(routing: Routing) {

			routing.route("kv/jobs/{jobId}") {

				fun PipelineContext<Unit,ApplicationCall>.parseJobId(): String =
					call.parameters.getOrFail("jobId")

				get("micrographs") {
					call.respondExceptions {
						
						val jobId = parseJobId()

						// NOTE: this needs to handle 10s of thousands of micrographs efficiently
						// this needs to stay simple and optimized!!
						val json: String = service.getMicrographs(jobId)
							.let { Json.encodeToString(it) }

						call.respondText(json, ContentType.Application.Json)
					}
				}

				get("tiltSerieses") {
					call.respondExceptions {

						val jobId = parseJobId()

						// NOTE: this needs to handle 10s of thousands of tilts efficiently
						// this needs to stay simple and optimized!!
						val json: String = service.getTiltSerieses(jobId)
							.let { Json.encodeToString(it) }

						call.respondText(json, ContentType.Application.Json)
					}
				}

				get("gainCorrectedImage/{size}") {
					call.respondExceptions {

						// parse args
						val jobId = parseJobId()
						val size = parseSize()

						val bytes = service.getGainCorrectedImage(jobId, size)

						call.respondBytes(bytes, ContentType.Image.WebP)
					}
				}

				route("data/{dataId}") {

					fun PipelineContext<Unit,ApplicationCall>.parseDataId(): String =
						call.parameters.getOrFail("dataId")

					get("image/{size}") {
						call.respondExceptions {

							// parse args
							val jobId = parseJobId()
							val dataId = parseDataId()
							val size = parseSize()

							val bytes = service.getImage(jobId, dataId, size)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}

					get("ctffind/{size}") {
						call.respondExceptions {

							// parse args
							val jobId = parseJobId()
							val dataId = parseDataId()
							val size = parseSize()

							val bytes = service.getCtffindImage(jobId, dataId, size)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}


					get("alignedTiltSeriesMontage") {
						call.respondExceptions {

							// parse args
							val jobId = parseJobId()
							val dataId = parseDataId()

							val bytes = service.getAlignedTiltSeriesMontage(jobId, dataId)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}

					get("reconstructionTiltSeriesMontage") {
						call.respondExceptions {

							// parse args
							val jobId = parseJobId()
							val dataId = parseDataId()

							val bytes = service.getReconstructionTiltSeriesMontage(jobId, dataId)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}

					get("2dCtfTiltMontage") {
						call.respondExceptions {

							// parse args
							val jobId = parseJobId()
							val dataId = parseDataId()

							val bytes = service.get2dCtfTiltMontage(jobId, dataId)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}

					get("rawTiltSeriesMontage") {
						call.respondExceptions {

							// parse args
							val jobId = parseJobId()
							val dataId = parseDataId()

							val bytes = service.getRawTiltSeriesMontage(jobId, dataId)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}

					get("sidesTiltSeriesImage") {
						call.respondExceptions {

							// parse args
							val jobId = parseJobId()
							val dataId = parseDataId()

							val bytes = service.getSidesTiltSeriesImage(jobId, dataId)

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}

					get("log") {
						call.respondExceptions {

							// parse args
							val jobId = parseJobId()
							val dataId = parseDataId()

							val log = service.getLog(jobId, dataId)

							call.respondText(log)
						}
					}

					get("virionThresholds/{virionId}") {
						call.respondExceptions {

							// parse args
							val jobId = parseJobId()
							val dataId = parseDataId()
							val virionId = call.parameters.getOrFail("virionId").toIntOrNull()
								?: throw BadRequestException("virion id must be a number")

							val bytes = service.virionThresholdsImage(jobId, dataId, virionId)
								?: throw NotFoundException()

							call.respondBytes(bytes, ContentType.Image.WebP)
						}
					}
				}
			}
		}

		private val PipelineContext<Unit,ApplicationCall>.service get() =
			getService<JobsService>()
	}

	@Inject
	override lateinit var call: ApplicationCall

	private fun String.authJob(permission: ProjectPermission): AuthInfo<Job> =
		authJob(permission, this)

	private fun Job.hasMicrographsOrThrow() = apply {
		if (baseConfig.jobInfo.dataType !== JobInfo.DataType.Micrograph) {
			throw IllegalArgumentException("job ${baseConfig.id} has no micrographs")
		}
	}

	private fun Job.hasTiltSeriesOrThrow() = apply {
		if (baseConfig.jobInfo.dataType !== JobInfo.DataType.TiltSeries) {
			throw IllegalArgumentException("job ${baseConfig.id} has no tilt-series")
		}
	}

	private fun getMicrographOrThrow(jobId: String, micrographId: String): Micrograph =
		Micrograph.get(jobId, micrographId)
			?: throw NoSuchElementException("no micrograph with id $micrographId found in job $jobId")

	private fun getTiltSeriesOrThrow(jobId: String, tiltSeries: String): TiltSeries =
		TiltSeries.get(jobId, tiltSeries)
			?: throw NoSuchElementException("no tilt series with id $tiltSeries found in job $jobId")

	override suspend fun getImagesScale(jobId: String): Option<ImagesScale> = sanitizeExceptions {
		val job = jobId.authJob(ProjectPermission.Read).job
		job.pypParameters()
			?.let { job.imagesScale(it) }
			.toOption()
	}

	fun getMicrographs(jobId: String): List<MicrographMetadata> {
		jobId.authJob(ProjectPermission.Read).job.hasMicrographsOrThrow()
		return Micrograph.getAll(jobId) { cursor ->
			cursor
				.map { it.getMetadata() }
				.toList()
		}
	}

	fun getTiltSerieses(jobId: String): List<TiltSeriesData> {
		jobId.authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
		return TiltSeries.getAll(jobId) { cursor ->
			cursor
				.map { it.getMetadata() }
				.toList()
		}
	}

	override suspend fun getMicrograph(jobId: String, micrographId: String): MicrographMetadata = sanitizeExceptions {
		jobId.authJob(ProjectPermission.Read).job.hasMicrographsOrThrow()
		return getMicrographOrThrow(jobId, micrographId).getMetadata()
	}

	override suspend fun getTiltSeries(jobId: String, tiltSeriesId: String): TiltSeriesData = sanitizeExceptions {
		jobId.authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
		return getTiltSeriesOrThrow(jobId, tiltSeriesId).getMetadata()
	}

	fun getImage(jobId: String, dataId: String, size: ImageSize): ByteArray {

		val job = jobId.authJob(ProjectPermission.Read).job

		return when (job.baseConfig.jobInfo.dataType) {
			JobInfo.DataType.Micrograph -> getMicrographOrThrow(jobId, dataId).readImage(job, size)
			JobInfo.DataType.TiltSeries -> getTiltSeriesOrThrow(jobId, dataId).readImage(job, size)
			else -> throw NoSuchElementException("job ${job.baseConfig.id} has no data type")
		}
	}

	override suspend fun getAvgRot(jobId: String, dataId: String): List<AvgRotData> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job
		val pypValues = job.pypParameters()
			?: return emptyList()

		return when (job.baseConfig.jobInfo.dataType) {
			JobInfo.DataType.Micrograph -> getMicrographOrThrow(jobId, dataId).getAvgRot()
			JobInfo.DataType.TiltSeries -> getTiltSeriesOrThrow(jobId, dataId).getAvgRot()
			else -> throw NoSuchElementException("job ${job.baseConfig.id} has no data type")
		}
			.let { listOf(it.data(pypValues)) }
	}

	override suspend fun getMotion(jobId: String, dataId: String): List<MotionData> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return when (job.baseConfig.jobInfo.dataType) {
			JobInfo.DataType.Micrograph -> getMicrographOrThrow(jobId, dataId).xf
			JobInfo.DataType.TiltSeries -> getTiltSeriesOrThrow(jobId, dataId).xf
			else -> throw NoSuchElementException("job ${job.baseConfig.id} has no data type")
		}
			.let { xf ->
				listOf(MotionData(
					x = xf.samples.map { it.x },
					y = xf.samples.map { it.y }
				))
			}
	}

	fun getCtffindImage(jobId: String, dataId: String, size: ImageSize): ByteArray {

		val job = jobId.authJob(ProjectPermission.Read).job

		return when (job.baseConfig.jobInfo.dataType) {
			JobInfo.DataType.Micrograph -> getMicrographOrThrow(jobId, dataId).readCtffindImage(job, size)
			JobInfo.DataType.TiltSeries -> getTiltSeriesOrThrow(jobId, dataId).readCtffindImage(job, size)
			else -> throw NoSuchElementException("job ${job.baseConfig.id} has no data type")
		}
	}

	override suspend fun getLog(jobId: String, dataId: String): String = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return when (job.baseConfig.jobInfo.dataType) {
			JobInfo.DataType.Micrograph -> getMicrographOrThrow(jobId, dataId).getLogs(job)
			JobInfo.DataType.TiltSeries -> getTiltSeriesOrThrow(jobId, dataId).getLogs(job)
			else -> throw NoSuchElementException("job ${job.baseConfig.id} has no data type")
		}
			.filter { it.timestamp != null && it.type == null }
			.maxByOrNull { it.timestamp!! }
			?.read()
			?: "(no log for ${job.baseConfig.jobInfo.dataType} $dataId)"
	}

	fun getAlignedTiltSeriesMontage(jobId: String, tiltSeriesId: String): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
		return getTiltSeriesOrThrow(jobId, tiltSeriesId).getAlignedTiltSeriesMontage(job.dir)
	}

	fun getReconstructionTiltSeriesMontage(jobId: String, tiltSeriesId: String): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
		return getTiltSeriesOrThrow(jobId, tiltSeriesId).getReconstructionTiltSeriesMontage(job.dir)
	}

	fun get2dCtfTiltMontage(jobId: String, tiltSeriesId: String): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
		return getTiltSeriesOrThrow(jobId, tiltSeriesId).get2dCtfTiltMontage(job.dir)
	}

	fun getRawTiltSeriesMontage(jobId: String, tiltSeriesId: String): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
		return getTiltSeriesOrThrow(jobId, tiltSeriesId).getRawTiltSeriesMontage(job.dir)
	}

	fun getSidesTiltSeriesImage(jobId: String, tiltSeriesId: String): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
		return getTiltSeriesOrThrow(jobId, tiltSeriesId).getSidesTiltSeriesImage(job.dir)
	}

	fun getGainCorrectedImage(jobId: String, size: ImageSize): ByteArray {

		val job = jobId.authJob(ProjectPermission.Read).job

		val imagePath = GainCorrectedImage.path(job)
		val cacheInfo = ImageCacheInfo(job.wwwDir, "gain-corrected")

		return size.readResize(imagePath, ImageType.Webp, cacheInfo)
			// no image, return a placeholder
			?: Resources.placeholderJpg(size)
	}

	override suspend fun getDriftMetadata(jobId: String, tiltSeriesId: String): Option<DriftMetadata> {
		val job = jobId.authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
		return job.pypParameters()
			?.let { getTiltSeriesOrThrow(jobId, tiltSeriesId).getDriftMetadata(it) }
			.toOption()
	}

	fun virionThresholdsImage(jobId: String, tiltSeriesId: String, virionId: Int): ByteArray? {
		val job = jobId.authJob(ProjectPermission.Read).job
		return getTiltSeriesOrThrow(jobId, tiltSeriesId).readVirionThresholdsImage(job, virionId)
	}

	override suspend fun getTiltExclusions(jobId: String, tiltSeriesId: String): TiltExclusions = sanitizeExceptions {
		jobId.authJob(ProjectPermission.Read).job
		return TiltExclusions(Database.tiltExclusions.getForTiltSeries(jobId, tiltSeriesId) ?: emptyMap())
	}

	override suspend fun setTiltExclusion(jobId: String, tiltSeriesId: String, tiltIndex: Int, value: Boolean) = sanitizeExceptions {
		jobId.authJob(ProjectPermission.Write).job
		Database.tiltExclusions.setForTilt(jobId, tiltSeriesId, tiltIndex, value)
	}

	override suspend fun pypStats(jobId: String): PypStats = sanitizeExceptions {
		val job = jobId.authJob(ProjectPermission.Read).job
		val argsValues = Database.parameters.getParams(jobId)
		return when (job.baseConfig.jobInfo.dataType) {
			JobInfo.DataType.Micrograph -> PypStats.fromSingleParticle(argsValues)
			JobInfo.DataType.TiltSeries -> PypStats.fromTomography(argsValues)
			else -> throw NoSuchElementException("job ${job.baseConfig.id} has no data type")
		}
	}
}
