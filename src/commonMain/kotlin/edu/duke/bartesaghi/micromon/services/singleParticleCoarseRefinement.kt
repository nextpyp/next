package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticleCoarseRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticleCoarseRefinementService {

	@KVBindingRoute("node/${SingleParticleCoarseRefinementNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inRefinement: CommonJobData.DataId, args: SingleParticleCoarseRefinementArgs): SingleParticleCoarseRefinementData

	@KVBindingRoute("node/${SingleParticleCoarseRefinementNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticleCoarseRefinementArgs?): SingleParticleCoarseRefinementData

	@KVBindingRoute("node/${SingleParticleCoarseRefinementNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticleCoarseRefinementData

	@KVBindingRoute("node/${SingleParticleCoarseRefinementNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}

@Serializable
data class SingleParticleCoarseRefinementArgs(
	val values: ArgValuesToml,
	val filter: String?
)

@Serializable
data class SingleParticleCoarseRefinementData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticleCoarseRefinementArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
