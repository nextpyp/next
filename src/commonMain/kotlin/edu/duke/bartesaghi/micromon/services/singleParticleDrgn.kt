package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticleDrgnNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticleDrgnService {

	@KVBindingRoute("node/${SingleParticleDrgnNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: SingleParticleDrgnArgs): SingleParticleDrgnData

	@KVBindingRoute("node/${SingleParticleDrgnNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticleDrgnArgs?): SingleParticleDrgnData

	@KVBindingRoute("node/${SingleParticleDrgnNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticleDrgnData

	@KVBindingRoute("node/${SingleParticleDrgnNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class SingleParticleDrgnArgs(
	val values: ArgValuesToml
)

@Serializable
data class SingleParticleDrgnData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticleDrgnArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
