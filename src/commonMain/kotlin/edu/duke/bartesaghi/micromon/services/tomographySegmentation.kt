package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographySegmentationNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographySegmentationService {

	@KVBindingRoute("node/${TomographySegmentationNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inTiltSeries: CommonJobData.DataId, args: TomographySegmentationArgs): TomographySegmentationData

	@KVBindingRoute("node/${TomographySegmentationNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographySegmentationArgs?): TomographySegmentationData

	@KVBindingRoute("node/${TomographySegmentationNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographySegmentationData

	@KVBindingRoute("node/${TomographySegmentationNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographySegmentationArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographySegmentationData(
	override val common: CommonJobData,
	val args: JobArgs<TomographySegmentationArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
}
