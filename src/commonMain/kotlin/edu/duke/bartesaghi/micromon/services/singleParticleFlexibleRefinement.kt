package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticleFlexibleRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticleFlexibleRefinementService {

	@KVBindingRoute("node/${SingleParticleFlexibleRefinementNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inRefinements: CommonJobData.DataId, args: SingleParticleFlexibleRefinementArgs): SingleParticleFlexibleRefinementData

	@KVBindingRoute("node/${SingleParticleFlexibleRefinementNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticleFlexibleRefinementArgs?): SingleParticleFlexibleRefinementData

	@KVBindingRoute("node/${SingleParticleFlexibleRefinementNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticleFlexibleRefinementData

	@KVBindingRoute("node/${SingleParticleFlexibleRefinementNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class SingleParticleFlexibleRefinementArgs(
	val values: ArgValuesToml
)

@Serializable
data class SingleParticleFlexibleRefinementData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticleFlexibleRefinementArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
