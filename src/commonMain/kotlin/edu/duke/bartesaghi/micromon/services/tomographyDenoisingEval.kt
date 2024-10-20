package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyDenoisingEvalNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyDenoisingEvalService {

	@KVBindingRoute("node/${TomographyDenoisingEvalNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inTomograms: CommonJobData.DataId, args: TomographyDenoisingEvalArgs): TomographyDenoisingEvalData

	@KVBindingRoute("node/${TomographyDenoisingEvalNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyDenoisingEvalArgs?): TomographyDenoisingEvalData

	@KVBindingRoute("node/${TomographyDenoisingEvalNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyDenoisingEvalData

	@KVBindingRoute("node/${TomographyDenoisingEvalNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
data class TomographyDenoisingEvalArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyDenoisingEvalData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyDenoisingEvalArgs>,
	val imageUrl: String,
	val numTiltSeries: Long
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}