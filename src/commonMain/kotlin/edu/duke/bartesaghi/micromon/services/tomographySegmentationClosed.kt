package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographySegmentationClosedNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographySegmentationClosedService {

	@KVBindingRoute("node/${TomographySegmentationClosedNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographySegmentationClosedArgs): TomographySegmentationClosedData

	@KVBindingRoute("node/${TomographySegmentationClosedNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographySegmentationClosedArgs?): TomographySegmentationClosedData

	@KVBindingRoute("node/${TomographySegmentationClosedNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographySegmentationClosedData

	@KVBindingRoute("node/${TomographySegmentationClosedNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
data class TomographySegmentationClosedArgs(
	val values: ArgValuesToml,
	val filter: String?
) {

	fun particlesList(jobId: String): ParticlesList =
		ParticlesList.autoVirions(jobId)
}


@Serializable
data class TomographySegmentationClosedData(
	override val common: CommonJobData,
	val args: JobArgs<TomographySegmentationClosedArgs>,
	val imageUrl: String,
	val numParticles: Long
) : JobData {
	override fun isChanged() = args.hasNext()
}
