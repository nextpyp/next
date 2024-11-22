package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticleFineRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticleFineRefinementService {

	@KVBindingRoute("node/${SingleParticleFineRefinementNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inRefinements: CommonJobData.DataId, args: SingleParticleFineRefinementArgs): SingleParticleFineRefinementData

	@KVBindingRoute("node/${SingleParticleFineRefinementNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticleFineRefinementArgs?): SingleParticleFineRefinementData

	@KVBindingRoute("node/${SingleParticleFineRefinementNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticleFineRefinementData

	@KVBindingRoute("node/${SingleParticleFineRefinementNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class SingleParticleFineRefinementArgs(
	val values: ArgValuesToml
)

@Serializable
data class SingleParticleFineRefinementData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticleFineRefinementArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
