package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyDrgnNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyDrgnService {

	@KVBindingRoute("node/${TomographyDrgnNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyDrgnArgs): TomographyDrgnData

	@KVBindingRoute("node/${TomographyDrgnNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyDrgnArgs?): TomographyDrgnData

	@KVBindingRoute("node/${TomographyDrgnNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyDrgnData

	@KVBindingRoute("node/${TomographyDrgnNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyDrgnArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyDrgnData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyDrgnArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
}
