package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyMiloTrainNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyMiloTrainService {

	@KVBindingRoute("node/${TomographyMiloTrainNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyMiloTrainArgs): TomographyMiloTrainData

	@KVBindingRoute("node/${TomographyMiloTrainNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyMiloTrainArgs?): TomographyMiloTrainData

	@KVBindingRoute("node/${TomographyMiloTrainNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyMiloTrainData

	@KVBindingRoute("node/${TomographyMiloTrainNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyMiloTrainArgs(
	val values: ArgValuesToml,
	val filter: String?
)

@Serializable
data class TomographyMiloTrainData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyMiloTrainArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
}
