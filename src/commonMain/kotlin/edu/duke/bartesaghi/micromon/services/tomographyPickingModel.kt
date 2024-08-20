package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyPickingModelNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyPickingModelService {

	@KVBindingRoute("node/${TomographyPickingModelNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyPickingModelArgs): TomographyPickingModelData

	@KVBindingRoute("node/${TomographyPickingModelNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyPickingModelArgs?): TomographyPickingModelData

	@KVBindingRoute("node/${TomographyPickingModelNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyPickingModelData

	@KVBindingRoute("node/${TomographyPickingModelNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyPickingModelArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyPickingModelData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyPickingModelArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
}
