
package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.WebCacheDir
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
import kotlin.io.path.div


actual class JobsService : IJobsService, Service {

	companion object {

		fun dataImageUrl(jobId: String, dataId: String, imageSize: ImageSize): String =
			"/kv/jobs/$jobId/data/$dataId/image/${imageSize.id}"

		fun gainCorrectedImageUrl(jobId: String, imageSize: ImageSize): String =
			"/kv/jobs/$jobId/gainCorrectedImage/${imageSize.id}"


		private fun Job.hasMicrographsOrThrow() = apply {
			if (baseConfig.jobInfo.dataType !== JobInfo.DataType.Micrograph) {
				throw BadRequestException("job ${baseConfig.id} has no micrographs")
			}
		}

		private fun Job.hasTiltSeriesOrThrow() = apply {
			if (baseConfig.jobInfo.dataType !== JobInfo.DataType.TiltSeries) {
				throw BadRequestException("job ${baseConfig.id} has no tilt-series")
			}
		}

		private fun getMicrographOrThrow(job: Job, micrographId: String): Micrograph =
			Micrograph.get(job.idOrThrow, micrographId)
				?: throw NoSuchElementException("no micrograph with id $micrographId found in job ${job.idOrThrow}")

		private fun getTiltSeriesOrThrow(job: Job, tiltSeries: String): TiltSeries =
			TiltSeries.get(job.idOrThrow, tiltSeries)
				?: throw NoSuchElementException("no tilt series with id $tiltSeries found in job ${job.idOrThrow}")


		fun init(routing: Routing) {

			routing.route("kv/jobs/{jobId}") {

				fun PipelineContext<Unit, ApplicationCall>.authJob(permission: ProjectPermission): AuthInfo<Job> {
					val jobId = call.parameters.getOrFail("jobId")
					return call.authJob(jobId, permission)
				}
				
				get("micrographs") {
					call.respondExceptions {
						
						val job = authJob(ProjectPermission.Read).job.hasMicrographsOrThrow()

						// NOTE: this needs to handle 10s of thousands of micrographs efficiently
						// this needs to stay simple and optimized!!
						call.respondTextWriter(ContentType.Application.Json) {
							// TODO: stream this response?
							//       might need to build the top layer of JSON manually though
							val micrographs = Micrograph.getAll(job.idOrThrow) { cursor ->
								cursor
									.map { it.getMetadata() }
									.toList()
							}
							write(Json.encodeToString(micrographs))
						}
					}
				}

				get("tiltSerieses") {
					call.respondExceptions {

						val job = authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()

						// NOTE: this needs to handle 10s of thousands of tilts efficiently
						// this needs to stay simple and optimized!!
						call.respondTextWriter(ContentType.Application.Json) {
							// TODO: stream this response?
							//       might need to build the top layer of JSON manually though
							val tiltSerieses = TiltSeries.getAll(job.idOrThrow) { cursor ->
								cursor
									.map { it.getMetadata() }
									.toList()
							}
							write(Json.encodeToString(tiltSerieses))
						}
					}
				}

				get("gainCorrectedImage/{size}") {
					call.respondExceptions {

						// parse args
						val job = authJob(ProjectPermission.Read).job
						val size = parseSize()

						// serve the image
						val imagePath = GainCorrectedImage.path(job)
						val cacheKey = WebCacheDir.Keys.gainCorrected
						ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
							?.respondPlaceholder(call, size)
					}
				}

				route("data/{dataId}") {

					fun PipelineContext<Unit,ApplicationCall>.parseDataId(): String =
						call.parameters.getOrFail("dataId")

					get("image/{size}") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val dataId = parseDataId()
							val size = parseSize()

							// serve the image
							val imagePath = when (job.baseConfig.jobInfo.dataType) {
								JobInfo.DataType.Micrograph -> Micrograph.outputImagePath(job.dir, dataId)
								JobInfo.DataType.TiltSeries -> TiltSeries.outputImagePath(job.dir, dataId)
								else -> throw NoSuchElementException("job ${job.baseConfig.id} has no data type")
							}
							val cacheKey = WebCacheDir.Keys.datum.parameterized(dataId)
							ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
								?.respondPlaceholder(call, size)
						}
					}

					get("ctffind/{size}") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val dataId = parseDataId()
							val size = parseSize()

							// serve the image
							val (imagePath, transformer) = when (job.baseConfig.jobInfo.dataType) {
								JobInfo.DataType.Micrograph ->
									Micrograph.ctffitImagePath(job.dir, dataId) to null
								JobInfo.DataType.TiltSeries ->
									TiltSeries.twodCtfTiltMontagePath(job.dir, dataId) to TiltSeries.montageCenterTiler(job.idOrThrow, dataId)
								else -> throw NoSuchElementException("job ${job.baseConfig.id} has no data type")
							}
							val cacheKey = WebCacheDir.Keys.ctffit.parameterized(dataId)
							ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey, transformer))
								?.respondPlaceholder(call, size)
						}
					}

					get("alignedTiltSeriesMontage") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
							val dataId = parseDataId()

							// serve the image
							val imagePath = TiltSeries.alignedMontagePath(job.dir, dataId)
							ImageType.Webp.respond(call, imagePath)
								?.respondNotFound(call)
						}
					}

					get("reconstructionTiltSeriesMontage") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
							val dataId = parseDataId()

							// serve the image
							val imagePath = TiltSeries.reconstructionTiltSeriesMontagePath(job.dir, dataId)
							ImageType.Webp.respond(call, imagePath)
								?.respondNotFound(call)
						}
					}

					get("2dCtfTiltMontage") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
							val dataId = parseDataId()

							// serve the image
							val imagePath = TiltSeries.twodCtfTiltMontagePath(job.dir, dataId)
							ImageType.Webp.respond(call, imagePath)
								?.respondNotFound(call)
						}
					}

					get("rawTiltSeriesMontage") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
							val dataId = parseDataId()

							// serve the image
							val imagePath = TiltSeries.rawTiltSeriesMontagePath(job.dir, dataId)
							ImageType.Webp.respond(call, imagePath)
								?.respondNotFound(call)
						}
					}

					get("sidesTiltSeriesImage") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
							val dataId = parseDataId()

							// serve the image
							val imagePath = TiltSeries.sidesImagePath(job.dir, dataId)
							ImageType.Webp.respond(call, imagePath)
								?.respondPlaceholder(call, ImageSize.Medium)
						}
					}

					get("virionThresholds/{virionId}") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val dataId = parseDataId()
							val virionId = call.parameters.getOrFail("virionId").toIntOrNull()
								?: throw BadRequestException("virion id must be a number")

							val imagePath = TiltSeries.virionThresholdsImagePath(job.dir, dataId, virionId)
							ImageType.Webp.respond(call, imagePath)
								?.respondPlaceholder(call, ImageSize.Medium)
						}
					}

					get("rec") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val dataId = parseDataId()

							val path = TiltSeries.recPath(job.dir, dataId)
							call.respondFile(path, ContentType.Application.OctetStream)
						}
					}

					get("tiltSeriesMetadata") {
						call.respondExceptions {

							// parse args
							val job = authJob(ProjectPermission.Read).job
							val dataId = parseDataId()

							val path = TiltSeries.metadataPath(job.dir, dataId)
							call.respondFile(path, ContentType.Application.OctetStream)
						}
					}
				}

				get("image/{size}") {
					call.respondExceptions {

						// parse args
						val job = authJob(ProjectPermission.Read).job
						val size = parseSize()

						// serve the image
						val imagePath = job.dir / "webp" / "out.webp"
						val cacheKey = WebCacheDir.Keys.output
						ImageType.Webp.respondSized(call, imagePath, size.info(job.wwwDir, cacheKey))
							?.respondPlaceholder(call, size)
					}
				}
			}
		}
	}

	@Inject
	override lateinit var call: ApplicationCall

	private fun String.authJob(permission: ProjectPermission): AuthInfo<Job> =
		authJob(this, permission)

	override suspend fun getMicrograph(jobId: String, micrographId: String): MicrographMetadata = sanitizeExceptions {
		val job = jobId.authJob(ProjectPermission.Read).job.hasMicrographsOrThrow()
		return getMicrographOrThrow(job, micrographId).getMetadata()
	}

	override suspend fun getTiltSeries(jobId: String, tiltSeriesId: String): TiltSeriesData = sanitizeExceptions {
		val job = jobId.authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
		return getTiltSeriesOrThrow(job, tiltSeriesId).getMetadata()
	}

	override suspend fun getAvgRot(jobId: String, dataId: String): List<AvgRotData> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job
		val pypValues = job.pypParameters()
			?: return emptyList()

		return when (job.baseConfig.jobInfo.dataType) {
			JobInfo.DataType.Micrograph -> getMicrographOrThrow(job, dataId).getAvgRot()
			JobInfo.DataType.TiltSeries -> getTiltSeriesOrThrow(job, dataId).getAvgRot()
			else -> throw NoSuchElementException("job ${job.baseConfig.id} has no data type")
		}
			.let { listOf(it.data(pypValues)) }
	}

	override suspend fun getMotion(jobId: String, dataId: String): List<MotionData> = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return when (job.baseConfig.jobInfo.dataType) {
			JobInfo.DataType.Micrograph -> getMicrographOrThrow(job, dataId).xf
			JobInfo.DataType.TiltSeries -> getTiltSeriesOrThrow(job, dataId).xf
			else -> throw NoSuchElementException("job ${job.baseConfig.id} has no data type")
		}
			.let { xf ->
				listOf(MotionData(
					x = xf.samples.map { it.x },
					y = xf.samples.map { it.y }
				))
			}
	}

	override suspend fun getLog(jobId: String, dataId: String): String = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return when (job.baseConfig.jobInfo.dataType) {
			JobInfo.DataType.Micrograph -> getMicrographOrThrow(job, dataId).getLogs(job)
			JobInfo.DataType.TiltSeries -> getTiltSeriesOrThrow(job, dataId).getLogs(job)
			else -> throw NoSuchElementException("job ${job.baseConfig.id} has no data type")
		}
			.filter { it.timestamp != null && it.type == null }
			.maxByOrNull { it.timestamp!! }
			?.read()
			?: "(no log for ${job.baseConfig.jobInfo.dataType} $dataId)"
	}

	override suspend fun getDriftMetadata(jobId: String, tiltSeriesId: String): Option<DriftMetadata> {
		val job = jobId.authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
		return job.pypParameters()
			?.let { getTiltSeriesOrThrow(job, tiltSeriesId).getDriftMetadata(it) }
			.toOption()
	}

	override suspend fun getTiltExclusions(jobId: String, tiltSeriesId: String): TiltExclusions = sanitizeExceptions {
		jobId.authJob(ProjectPermission.Read).job
		return TiltExclusions(Database.instance.tiltExclusions.getForTiltSeries(jobId, tiltSeriesId) ?: emptyMap())
	}

	override suspend fun setTiltExclusion(jobId: String, tiltSeriesId: String, tiltIndex: Int, value: Boolean) = sanitizeExceptions {
		jobId.authJob(ProjectPermission.Write).job
		Database.instance.tiltExclusions.setForTilt(jobId, tiltSeriesId, tiltIndex, value)
	}

	override suspend fun pypStats(jobId: String): PypStats = sanitizeExceptions {
		jobId.authJob(ProjectPermission.Read).job
		val argsValues = Database.instance.parameters.getParams(jobId)
		PypStats.fromArgValues(argsValues)
	}

	override suspend fun recData(jobId: String, tiltSeriesId: String): Option<FileDownloadData> = sanitizeExceptions {
		val job = jobId.authJob(ProjectPermission.Read).job.hasTiltSeriesOrThrow()
		TiltSeries.recPath(job.dir, tiltSeriesId)
			.toFileDownloadData()
			.toOption()
	}

	override suspend fun findTiltSeriesMetadataData(jobId: String, tiltSeriesId: String): Option<FileDownloadData> = sanitizeExceptions {

		// NOTE: this is a user search function, so don't throw errors
		//       return null (as an option) instead

		val job = jobId.authJob(ProjectPermission.Read).job
			.takeIf { it.baseConfig.jobInfo.dataType == JobInfo.DataType.TiltSeries }
			?: return null.toOption()

		// NOTE: The tilt series won't exist in the refinement job (it's in the preprocessing job),
		//       so don't try to look for it here. Just look for the metadata file among this job's files

		return TiltSeries.metadataPath(job.dir, tiltSeriesId)
			.toFileDownloadData()
			.toOption()
	}

	override suspend fun getAllArgs(): Serialized<Args> = sanitizeExceptions {
		Backend.instance.pypArgsWithMicromon
			.toJson()
	}
}
