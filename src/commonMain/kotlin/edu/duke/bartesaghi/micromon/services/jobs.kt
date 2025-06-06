package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.pyp.*
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface IJobsService {

	@KVBindingRoute("jobs/micrograph")
	suspend fun getMicrograph(jobId: String, micrographId: String): MicrographMetadata

	@KVBindingRoute("jobs/tiltSeries")
	suspend fun getTiltSeries(jobId: String, tiltSeriesId: String): TiltSeriesData

	@KVBindingRoute("jobs/avgrot")
	suspend fun getAvgRot(jobId: String, dataId: String): List<AvgRotData>

	@KVBindingRoute("jobs/motion")
	suspend fun getMotion(jobId: String, dataId: String): List<MotionData>

	@KVBindingRoute("jobs/log")
	suspend fun getLog(jobId: String, dataId: String): String

	@KVBindingRoute("jobs/driftMetadata")
	suspend fun getDriftMetadata(jobId: String, tiltSeriesId: String): Option<DriftMetadata>

	@KVBindingRoute("jobs/getTiltExclusions")
	suspend fun getTiltExclusions(jobId: String, tiltSeriesId: String): TiltExclusions

	@KVBindingRoute("jobs/setTiltExclusion")
	suspend fun setTiltExclusion(jobId: String, tiltSeriesId: String, tiltIndex: Int, value: Boolean)

	@KVBindingRoute("jobs/pypStats")
	suspend fun pypStats(jobId: String): PypStats

	@KVBindingRoute("jobs/rec")
	suspend fun recData(jobId: String, tiltSeriesId: String): Option<FileDownloadData>

	@KVBindingRoute("jobs/tiltSeriesMetadata")
	suspend fun findTiltSeriesMetadataData(jobId: String, tiltSeriesId: String): Option<FileDownloadData>

	@KVBindingRoute("jobs/allArgs")
	suspend fun getAllArgs(): Serialized<Args>

	companion object {

		fun outputImage(jobId: String, size: ImageSize): String =
			"/kv/jobs/$jobId/image/${size.id}"
	}
}


@Serializable
data class TiltExclusions(
	val exclusionsByTiltIndex: Map<Int,Boolean>
)

@Serializable
data class PypStats(
	val scopeVoltage: Double?,
	val scopePixel: ValueA?,
	val scopeDoseRate: Double?
) {

	companion object {

		fun fromArgValues(values: ArgValues?) =
			PypStats(
				values?.scopeVoltage,
				values?.scopePixel,
				values?.scopeDoseRateOrDefault
			)
	}
}


@Serializable
enum class FileUploadOperation(val id: String) {
	Set("set"),
	Get("set"),
	Delete("delete"),
	Data("data")
}


@Serializable
data class FileUploadData(
	val bytes: Long
)
