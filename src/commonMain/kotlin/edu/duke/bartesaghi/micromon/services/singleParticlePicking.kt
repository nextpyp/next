package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticlePickingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticlePickingService {

	@KVBindingRoute("node/${SingleParticlePickingNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: SingleParticlePickingArgs): SingleParticlePickingData

	@KVBindingRoute("node/${SingleParticlePickingNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticlePickingArgs?): SingleParticlePickingData

	@KVBindingRoute("node/${SingleParticlePickingNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticlePickingData

	@KVBindingRoute("node/${SingleParticlePickingNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class SingleParticlePickingArgs(
	val values: ArgValuesToml
)

@Serializable
data class SingleParticlePickingData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticlePickingArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
