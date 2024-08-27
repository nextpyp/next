package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographySegmentationClosedNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.toArgValues
import edu.duke.bartesaghi.micromon.pyp.tomoSrfMethodOrDefault
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographySegmentationClosedService {

	@KVBindingRoute("node/${TomographySegmentationClosedNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographySegmentationClosedArgs): TomographySegmentationClosedData

	@KVBindingRoute("node/${TomographySegmentationClosedNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographySegmentationClosedArgs?): TomographySegmentationClosedData

	@KVBindingRoute("node/${TomographySegmentationClosedNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographySegmentationClosedData

	@KVBindingRoute("node/${TomographySegmentationClosedNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographySegmentationClosedArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographySegmentationClosedData(
	override val common: CommonJobData,
	val args: JobArgs<TomographySegmentationClosedArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
}
