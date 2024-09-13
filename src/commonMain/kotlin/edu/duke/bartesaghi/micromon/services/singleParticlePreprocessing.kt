package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticlePreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.ImageDims
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticlePreprocessingService {

	@KVBindingRoute("node/${SingleParticlePreprocessingNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inMovies: CommonJobData.DataId, args: SingleParticlePreprocessingArgs): SingleParticlePreprocessingData

	@KVBindingRoute("node/${SingleParticlePreprocessingNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticlePreprocessingArgs?): SingleParticlePreprocessingData

	@KVBindingRoute("node/${SingleParticlePreprocessingNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticlePreprocessingData

	@KVBindingRoute("node/${SingleParticlePreprocessingNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class SingleParticlePreprocessingArgs(
	val values: ArgValuesToml
)

@Serializable
data class SingleParticlePreprocessingData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticlePreprocessingArgs>,
	val imageUrl: String,
	val numMicrographs: Long,
	val numParticles: Long
) : JobData {
	override fun isChanged() = args.hasNext()
}

@Serializable
data class MicrographMetadata(
	override val id: String,
	override val timestamp: Long,
	override val ccc: Double,
	override val cccc: Double,
	override val defocus1: Double,
	override val defocus2: Double,
	override val angleAstig: Double,
	override val averageMotion: Double,
	override val numAutoParticles: Int,
	override val imageDims: ImageDims
) : PreprocessingData {

	fun imageUrl(job: JobData, size: ImageSize): String =
		"/kv/jobs/${job.jobId}/data/$id/image/${size.id}"

	fun imageUrl(session: SessionData, size: ImageSize): String =
		"/kv/sessions/${session.sessionId}/data/$id/image/${size.id}"

	fun ctffindUrl(job: JobData, size: ImageSize): String =
		"/kv/jobs/${job.jobId}/data/$id/ctffind/${size.id}"

	fun ctffindUrl(session: SessionData, size: ImageSize): String =
		"/kv/sessions/${session.sessionId}/data/$id/ctffind/${size.id}"
}

@Serializable
data class AvgRotData(
	val spatialFreq: List<Double>,
	val avgRot: List<Double>,
	val ctfFit: List<Double>,
	val crossCorrelation: List<Double>,
	val minRes: Double
)

@Serializable
data class MotionData(
	val x: List<Double>,
	val y: List<Double>
)


@Serializable
enum class MicrographProp(
	override val label: String
) : PreprocessingDataProperty {

	Time("Time"),
	CCC("CTF Fit"),
	CCCC("Est. Resolution"),
	Defocus1("Defocus 1"),
	Defocus2("Defocus 2"),
	AngleAstig("Angast"),
	AverageMotion("Avgerage Motion"),
	NumParticles("Particles");

	override val id = "micrograph/${name.lowercase()}"

	companion object {

		operator fun get(id: String) =
			values()
				.find { it.id == id }
				?: throw NoSuchElementException("no micrograph property with id $id")
	}
}


@Serializable
data class TwoDClassesData(
	val ownerId: String,
	val twoDClassesId: String,
	val created: Long
) {

	fun imageUrlSession(imageSize: ImageSize): String =
		"/kv/sessions/$ownerId/2dClasses/$twoDClassesId/image/${imageSize.id}"
}
