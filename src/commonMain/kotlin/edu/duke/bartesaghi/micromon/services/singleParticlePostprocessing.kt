package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticlePostprocessingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticlePostprocessingService {

	@KVBindingRoute("node/${SingleParticlePostprocessingNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inRefinements: CommonJobData.DataId, args: SingleParticlePostprocessingArgs): SingleParticlePostprocessingData

	@KVBindingRoute("node/${SingleParticlePostprocessingNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticlePostprocessingArgs?): SingleParticlePostprocessingData

	@KVBindingRoute("node/${SingleParticlePostprocessingNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticlePostprocessingData

	@KVBindingRoute("node/${SingleParticlePostprocessingNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class SingleParticlePostprocessingArgs(
	val values: ArgValuesToml
)

@Serializable
data class SingleParticlePostprocessingData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticlePostprocessingArgs>,
	val imageURL: String,
	val bfactor: Double?
) : JobData {
	override fun isChanged() = args.hasNext()
}
