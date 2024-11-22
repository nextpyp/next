package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyDenoisingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyDenoisingService {

	@KVBindingRoute("node/${TomographyDenoisingNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inModel: CommonJobData.DataId, args: TomographyDenoisingArgs): TomographyDenoisingData

	@KVBindingRoute("node/${TomographyDenoisingNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyDenoisingArgs?): TomographyDenoisingData

	@KVBindingRoute("node/${TomographyDenoisingNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyDenoisingData

	@KVBindingRoute("node/${TomographyDenoisingNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyDenoisingArgs(
	val values: ArgValuesToml,
	val filter: String?
)

@Serializable
data class TomographyDenoisingData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyDenoisingArgs>,
	val imageUrl: String,
	val numTiltSeries: Long
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
