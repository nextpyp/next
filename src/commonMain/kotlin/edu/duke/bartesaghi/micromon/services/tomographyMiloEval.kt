package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyMiloEvalNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyMiloEvalService {

	@KVBindingRoute("node/${TomographyMiloEvalNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyMiloEvalArgs): TomographyMiloEvalData

	@KVBindingRoute("node/${TomographyMiloEvalNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyMiloEvalArgs?): TomographyMiloEvalData

	@KVBindingRoute("node/${TomographyMiloEvalNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyMiloEvalData

	@KVBindingRoute("node/${TomographyMiloEvalNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyMiloEvalArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyMiloEvalData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyMiloEvalArgs>,
	val imageUrl: String,
	val numParticles: Long
) : JobData {
	override fun isChanged() = args.hasNext()
}
