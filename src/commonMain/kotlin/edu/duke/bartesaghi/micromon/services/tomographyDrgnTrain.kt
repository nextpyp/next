package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.divideUp
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
	suspend fun classMrcData(jobId: String, epoch: Int, classNum: Int): Option<FileDownloadData>


	companion object {

		fun plotPath(jobId: String, number: Int): String =
			"/kv/node/${TomographyDrgnTrainNodeConfig.ID}/$jobId/plot/$number"

		fun distributionPath(jobId: String): String =
			"/kv/node/${TomographyDrgnTrainNodeConfig.ID}/$jobId/distribution"

		fun pairwiseCCMatrixPath(jobId: String, checkpoint: Int): String =
			"/kv/node/${TomographyDrgnTrainNodeConfig.ID}/$jobId/checkpoint/$checkpoint/pairwiseCCMatrix"

		fun classImagePath(jobId: String, epoch: Int, classNum: Int, size: ImageSize) =
			"/kv/node/${TomographyDrgnTrainNodeConfig.ID}/$jobId/epoch/$epoch/class/$classNum/image/${size.id}"

		fun classMrcPath(jobId: String, epoch: Int, classNum: Int) =
			"/kv/node/${TomographyDrgnTrainNodeConfig.ID}/$jobId/epoch/$epoch/class/$classNum/mrc"
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
		val epochInterval: Int,
		val finalMaxima: Int,
	) {

		// alias some pyp parameter names so they make more sense in the website context
		val numClasses: Int get() = finalMaxima
	}

	@Serializable
	data class Iteration(
		val epoch: Int,
		val timestamp: Long
	) {

		fun checkpoint(params: Parameters): Int =
			epoch.divideUp(params.epochInterval)
	}

	fun epochs(): Set<Int> =
		iterations
			.map { it.epoch }
			.toSet()
}
