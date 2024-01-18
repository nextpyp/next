package edu.duke.bartesaghi.micromon.services

import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface IIntegratedRefinementService {

    @KVBindingRoute("refinements/getReconstruction")
    suspend fun getReconstruction(jobId: String, reconstructionId: String): ReconstructionData

    @KVBindingRoute("refinements/getReconstructions")
    suspend fun getReconstructions(jobId: String): List<ReconstructionData>

    @KVBindingRoute("refinements/getReconstructionPlotsData")
    suspend fun getReconstructionPlotsData(jobId: String, reconstructionId: String): ReconstructionPlotsData

	@KVBindingRoute("refinements/getLog")
	suspend fun getLog(jobId: String, reconstructionId: String): String

	@KVBindingRoute("refinements/get")
	suspend fun getRefinements(jobId: String): List<RefinementData>

	@KVBindingRoute("refinementBundles/get")
	suspend fun getRefinementBundles(jobId: String): List<RefinementBundleData>

	@KVBindingRoute("refinements/bildData")
	suspend fun getBildData(jobId: String, reconstructionId: String): Option<BildData>
}


@Serializable
data class ReconstructionData(
    override val id: String,
    val timestamp: Long,
    val classNum: Int,
    val iteration: Int
) : HasID

/**
 * different from ReconstructionData
 * perfectly clear, right?
 */
@Serializable
data class ReconstructionMetaData(
	val particlesTotal: Double,
	val particlesUsed: Double,
	val phaseResidual: Double,
	val occ: Double,
	val logp: Double,
	val sigma: Double
) {
	// define the companion object, so non-common code can extend it
	companion object
}

@Serializable
data class ReconstructionPlots(
	val defRotHistogram: List<List<Double>>,
	val defRotScores: List<List<Double>>,
	val rotHist: Histogram,
	val defHist: Histogram,
	val scoresHist: Histogram,
	val occHist: Histogram,
	val logpHist: Histogram,
	val sigmaHist: Histogram,
	val occPlot: List<Double>? = null
) {

	// define the companion object, so non-common code can extend it
	companion object

	@Serializable
	data class Histogram(
		val n: List<Double>,
		val bins: List<Double>
	) {
		// define the companion object, so non-common code can extend it
		companion object
	}
}

@Serializable
class ReconstructionPlotsData(
	val plots: ReconstructionPlots,
	val metadata: ReconstructionMetaData,
	val fsc: List<List<Double>>
)


class Reconstructions {

	class ByIteration(val iteration: Int) {

		private val list = ArrayList<ReconstructionData>()

		private val byClass = HashMap<Int,ReconstructionData>()

		val classes: List<Int> get() =
			byClass.keys.sorted()

		/** returns all the reconstructions, in class number order */
		fun all(): List<ReconstructionData> =
			classes.map { byClass[it]!! }

		fun withClass(classNum: Int): ReconstructionData? =
			byClass[classNum]

		fun add(reconstruction: ReconstructionData) {
			list.add(reconstruction)
			byClass[reconstruction.classNum] = reconstruction
		}
	}

	private val byId = HashMap<String,ReconstructionData>()
	private val byIteration = HashMap<Int,ByIteration>()

	val iterations: List<Int> get() =
		byIteration.keys.sorted()

	fun hasIteration(iteration: Int): Boolean =
		byIteration.containsKey(iteration)

	fun withIteration(iteration: Int): ByIteration? =
		byIteration[iteration]

	/** returns all the reconstructions, but sorted by iteration, then class */
	fun all(): List<ReconstructionData> =
		iterations.flatMap { iteration ->
			byIteration[iteration]!!.all()
		}

	fun add(reconstruction: ReconstructionData) {
		byId[reconstruction.id] = reconstruction
		byIteration.getOrPut(reconstruction.iteration) { ByIteration(reconstruction.iteration) }
			.add(reconstruction)
	}

	fun maxNumClasses(): Int =
		byIteration.values
			.maxOfOrNull { it.classes.size }
			?: 0
}

/**
 * Keep up with MRC file suffixes
 */
enum class MRCType(val id: String, val filenameFragment: String?, val usesIteration: Boolean) {

	HALF1("half1", "half1", false),
	HALF2("half2", "half2", false),
	CROP("crop","crop", true),
	FULL("full", null, true);

	companion object {

		operator fun get(id: String): MRCType? =
			values().find { it.id == id }
	}
}


@Serializable
data class RefinementData(
	val jobId: String,
	/** eg a micrograph id or a tilt series id */
	val dataId: String,
	override val iteration: Int,
	val timestamp: Long
) : HasIDIterated {

	override val id = dataId
}


@Serializable
data class RefinementBundleData(
	val jobId: String,
	val refinementBundleId: String,
	override val iteration: Int,
	val timestamp: Long
) : HasIDIterated {

	override val id = refinementBundleId
}


interface HasIDIterated : HasID {
	val iteration: Int
}


@Serializable
data class BildData(
	val bytes: Long
)
