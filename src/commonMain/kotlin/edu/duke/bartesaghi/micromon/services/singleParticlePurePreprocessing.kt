package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticlePurePreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticlePurePreprocessingService {

	@KVBindingRoute("node/${SingleParticlePurePreprocessingNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: SingleParticlePurePreprocessingArgs): SingleParticlePurePreprocessingData

	@KVBindingRoute("node/${SingleParticlePurePreprocessingNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticlePurePreprocessingArgs?): SingleParticlePurePreprocessingData

	@KVBindingRoute("node/${SingleParticlePurePreprocessingNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticlePurePreprocessingData

	@KVBindingRoute("node/${SingleParticlePurePreprocessingNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
data class SingleParticlePurePreprocessingArgs(
	val values: ArgValuesToml
)

@Serializable
data class SingleParticlePurePreprocessingData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticlePurePreprocessingArgs>,
	val imageUrl: String,
	val numMicrographs: Long
) : JobData {
	override fun isChanged() = args.hasNext()
}
