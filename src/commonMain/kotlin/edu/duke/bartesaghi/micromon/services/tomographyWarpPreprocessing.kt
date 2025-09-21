package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyWarpPreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyWarpPreprocessingService {

	@KVBindingRoute("node/${TomographyWarpPreprocessingNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inTiltSeries: CommonJobData.DataId, args: TomographyWarpPreprocessingArgs): TomographyWarpPreprocessingData

	@KVBindingRoute("node/${TomographyWarpPreprocessingNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyWarpPreprocessingArgs?): TomographyWarpPreprocessingData

	@KVBindingRoute("node/${TomographyWarpPreprocessingNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyWarpPreprocessingData

	@KVBindingRoute("node/${TomographyWarpPreprocessingNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyWarpPreprocessingArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyWarpPreprocessingData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyWarpPreprocessingArgs>,
	val imageUrl: String,
	val numTiltSeries: Long
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
