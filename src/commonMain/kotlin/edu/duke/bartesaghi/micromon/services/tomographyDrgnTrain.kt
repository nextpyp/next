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
