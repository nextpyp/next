package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyPreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.ImageDims
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyPreprocessingService {

	@KVBindingRoute("node/${TomographyPreprocessingNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inTiltSeries: CommonJobData.DataId, args: TomographyPreprocessingArgs): TomographyPreprocessingData

	@KVBindingRoute("node/${TomographyPreprocessingNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyPreprocessingArgs?): TomographyPreprocessingData

	@KVBindingRoute("node/${TomographyPreprocessingNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyPreprocessingData

	@KVBindingRoute("node/${TomographyPreprocessingNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
data class TomographyPreprocessingArgs(
	val values: ArgValuesToml,
	val tomolist: String?
)

@Serializable
data class TomographyPreprocessingData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyPreprocessingArgs>,
	val imageUrl: String,
	val numTiltSeries: Long,
	val numParticles: Long
) : JobData {
	override fun isChanged() = args.hasNext()
}


@Serializable
data class TiltSeriesData(
	override val id: String,
	override val timestamp: Long,
	override val ccc: Double,
	override val cccc: Double,
	override val defocus1: Double,
	override val defocus2: Double,
	override val angleAstig: Double,
	override val averageMotion: Double,
	override val numAutoParticles: Int,
	/** used only by older combined preprocessing blocks */
	val numAutoVirions: Int,
	override val imageDims: ImageDims
) : PreprocessingData {

	fun imageUrl(job: JobData, imageSize: ImageSize): String =
		"/kv/jobs/${job.jobId}/data/$id/image/${imageSize.id}"

	fun imageUrl(session: SessionData, imageSize: ImageSize): String =
		"/kv/sessions/${session.sessionId}/data/$id/image/${imageSize.id}"

	fun ctffindUrl(job: JobData, size: ImageSize): String =
		"/kv/jobs/${job.jobId}/data/$id/ctffind/${size.id}"

	fun ctffindUrl(session: SessionData, size: ImageSize): String =
		"/kv/sessions/${session.sessionId}/data/$id/ctffind/${size.id}"
}


@Serializable
data class DriftMetadata(
	val tilts: List<Double>,
	val drifts: List<List<DriftXY>>,
	val ctfValues: List<CtfTiltValues>,
	val ctfProfiles: List<AvgRotData>,
	val tiltAxisAngle: Double
)

@Serializable
data class DriftXY(
	val x: Double,
	val y: Double
)

@Serializable
data class CtfTiltValues(
	val index: Int,
	val defocus1: Double,
	val defocus2: Double,
	val astigmatism: Double,
	val cc: Double,
	val resolution: Double
)


@Serializable
enum class TiltSeriesProp(
	override val label: String
) : PreprocessingDataProperty {

	// TODO: how are these different from micrographs??
	Time("Time"),
	CCC("CTF Score"),
	CCCC("Est. Resolution"),
	Defocus1("Defocus 1"),
	Defocus2("Defocus 2"),
	AngleAstig("Angast"),
	AverageMotion("Avg Motion"),
	NumParticles("Particles");

	override val id = "tiltSeries/${name.lowercase()}"

	companion object {

		operator fun get(id: String) =
			values()
				.find { it.id == id }
				?: throw NoSuchElementException("no tilt series property with id $id")
	}
}
