package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyDenoisingTrainingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyDenoisingTrainingService {

	@KVBindingRoute("node/${TomographyDenoisingTrainingNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inTomograms: CommonJobData.DataId, args: TomographyDenoisingTrainingArgs): TomographyDenoisingTrainingData

	@KVBindingRoute("node/${TomographyDenoisingTrainingNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyDenoisingTrainingArgs?): TomographyDenoisingTrainingData

	@KVBindingRoute("node/${TomographyDenoisingTrainingNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyDenoisingTrainingData

	@KVBindingRoute("node/${TomographyDenoisingTrainingNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */


	companion object {

		fun trainResultsPath(jobId: String): String =
			"/kv/node/${TomographyDenoisingTrainingNodeConfig.ID}/$jobId/train_results"

	}
}


@Serializable
data class TomographyDenoisingTrainingArgs(
	val values: ArgValuesToml,
	val filter: String?
)

@Serializable
data class TomographyDenoisingTrainingData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyDenoisingTrainingArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
