package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyParticlesEvalNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyParticlesEvalService {

	@KVBindingRoute("node/${TomographyParticlesEvalNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyParticlesEvalArgs): TomographyParticlesEvalData

	@KVBindingRoute("node/${TomographyParticlesEvalNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyParticlesEvalArgs?): TomographyParticlesEvalData

	@KVBindingRoute("node/${TomographyParticlesEvalNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyParticlesEvalData

	@KVBindingRoute("node/${TomographyParticlesEvalNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyParticlesEvalArgs(
	val values: ArgValuesToml,
	val filter: String?
) {

	fun particlesList(jobId: String): ParticlesList =
		ParticlesList.autoParticles3D(jobId)
}


@Serializable
data class TomographyParticlesEvalData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyParticlesEvalArgs>,
	val imageUrl: String,
	val numParticles: Long
) : JobData {
	override fun isChanged() = args.hasNext()
}
