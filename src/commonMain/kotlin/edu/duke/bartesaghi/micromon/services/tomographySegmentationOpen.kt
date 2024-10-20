package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographySegmentationOpenNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographySegmentationOpenService {

	@KVBindingRoute("node/${TomographySegmentationOpenNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographySegmentationOpenArgs): TomographySegmentationOpenData

	@KVBindingRoute("node/${TomographySegmentationOpenNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographySegmentationOpenArgs?): TomographySegmentationOpenData

	@KVBindingRoute("node/${TomographySegmentationOpenNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographySegmentationOpenData

	@KVBindingRoute("node/${TomographySegmentationOpenNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
data class TomographySegmentationOpenArgs(
	val values: ArgValuesToml,
	val filter: String?
)

@Serializable
data class TomographySegmentationOpenData(
	override val common: CommonJobData,
	val args: JobArgs<TomographySegmentationOpenArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
