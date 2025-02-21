package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyDrgnTrainNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyDrgnTrainService {

	@KVBindingRoute("node/${TomographyDrgnTrainNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyDrgnTrainArgs): TomographyDrgnTrainData

	@KVBindingRoute("node/${TomographyDrgnTrainNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyDrgnTrainArgs?): TomographyDrgnTrainData

	@KVBindingRoute("node/${TomographyDrgnTrainNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyDrgnTrainData

	@KVBindingRoute("node/${TomographyDrgnTrainNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */

	@KVBindingRoute("node/${TomographyDrgnTrainNodeConfig.ID}/convergence")
	suspend fun getConvergence(jobId: String): Option<TomoDrgnConvergence>

	@KVBindingRoute("node/${TomographyDrgnTrainNodeConfig.ID}/classMrcData")
	suspend fun classMrcData(jobId: String, iterNum: Int, classNum: Int): Option<FileDownloadData>


	companion object {

		fun plotPath(jobId: String, number: Int): String =
			"/kv/node/${TomographyDrgnTrainNodeConfig.ID}/$jobId/plot/$number"

		fun distributionPath(jobId: String): String =
			"/kv/node/${TomographyDrgnTrainNodeConfig.ID}/$jobId/distribution"

		fun pairwiseCCMatrixPath(jobId: String, epoch: Int): String =
			"/kv/node/${TomographyDrgnTrainNodeConfig.ID}/$jobId/pairwiseCCMatrix/$epoch"

		fun classImagePath(jobId: String, iterNum: Int, classNum: Int, size: ImageSize) =
			"/kv/node/${TomographyDrgnTrainNodeConfig.ID}/$jobId/iter/$iterNum/class/$classNum/image/${size.id}"

		fun classMrcPath(jobId: String, iterNum: Int, classNum: Int) =
			"/kv/node/${TomographyDrgnTrainNodeConfig.ID}/$jobId/iter/$iterNum/class/$classNum/mrc"
	}
}


@Serializable
data class TomographyDrgnTrainArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyDrgnTrainData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyDrgnTrainArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}


@Serializable
data class TomoDrgnConvergence(
	val parameters: Parameters,
	val iterations: List<Iteration>
) {

	@Serializable
	data class Parameters(
		val epochs: Int,
		val epochIndex: String,
		val epochInterval: Int,
		val finalMaxima: Int,
	) {

		// alias some pyp parameter names so they make more sense in the website context
		val numClasses: Int get() = finalMaxima

		fun epochRange(): IntRange =
			if (epochIndex == "latest") {
				0 until epochs
			} else {
				val i = epochIndex.toIntOrNull()
					?: throw IllegalArgumentException("invalid epoch index: $epochIndex")
				0 .. i
			}
	}

	@Serializable
	data class Iteration(
		val number: Int,
		val timestamp: Long
	)

	fun iterationNumbers(): Set<Int> =
		iterations
			.map { it.number }
			.toSet()

	fun epoch(iter: Iteration): Int =
		epoch(iter.number)
	fun epoch(iter: Int): Int =
		iter*parameters.epochInterval
}
