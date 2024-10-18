package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticleMaskingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticleMaskingService {

	@KVBindingRoute("node/${SingleParticleMaskingNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inRefinements: CommonJobData.DataId, args: SingleParticleMaskingArgs): SingleParticleMaskingData

	@KVBindingRoute("node/${SingleParticleMaskingNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticleMaskingArgs?): SingleParticleMaskingData

	@KVBindingRoute("node/${SingleParticleMaskingNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticleMaskingData

	@KVBindingRoute("node/${SingleParticleMaskingNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
data class SingleParticleMaskingArgs(
	val values: ArgValuesToml
)

@Serializable
data class SingleParticleMaskingData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticleMaskingArgs>,
	val imageURL: String,
	val bfactor: Double?
) : JobData {
	override fun isChanged() = args.hasNext()
}
