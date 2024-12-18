package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyDrgnEvalNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyDrgnEvalService {

	@KVBindingRoute("node/${TomographyDrgnEvalNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyDrgnEvalArgs): TomographyDrgnEvalData

	@KVBindingRoute("node/${TomographyDrgnEvalNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyDrgnEvalArgs?): TomographyDrgnEvalData

	@KVBindingRoute("node/${TomographyDrgnEvalNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyDrgnEvalData

	@KVBindingRoute("node/${TomographyDrgnEvalNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyDrgnEvalArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyDrgnEvalData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyDrgnEvalArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
