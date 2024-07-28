package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticleDenoisingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticleDenoisingService {

	@KVBindingRoute("node/${SingleParticleDenoisingNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: SingleParticleDenoisingArgs): SingleParticleDenoisingData

	@KVBindingRoute("node/${SingleParticleDenoisingNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticleDenoisingArgs?): SingleParticleDenoisingData

	@KVBindingRoute("node/${SingleParticleDenoisingNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticleDenoisingData

	@KVBindingRoute("node/${SingleParticleDenoisingNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class SingleParticleDenoisingArgs(
	val values: ArgValuesToml
)

@Serializable
data class SingleParticleDenoisingData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticleDenoisingArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
}
