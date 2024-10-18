package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyPurePreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyPurePreprocessingService {

	@KVBindingRoute("node/${TomographyPurePreprocessingNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inTiltSeries: CommonJobData.DataId, args: TomographyPurePreprocessingArgs): TomographyPurePreprocessingData

	@KVBindingRoute("node/${TomographyPurePreprocessingNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyPurePreprocessingArgs?): TomographyPurePreprocessingData

	@KVBindingRoute("node/${TomographyPurePreprocessingNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyPurePreprocessingData

	@KVBindingRoute("node/${TomographyPurePreprocessingNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
data class TomographyPurePreprocessingArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyPurePreprocessingData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyPurePreprocessingArgs>,
	val imageUrl: String,
	val numTiltSeries: Long
) : JobData {
	override fun isChanged() = args.hasNext()
}
