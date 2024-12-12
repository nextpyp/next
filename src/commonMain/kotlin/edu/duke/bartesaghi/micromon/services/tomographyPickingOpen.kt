package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyPickingOpenNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyPickingOpenService {

	@KVBindingRoute("node/${TomographyPickingOpenNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyPickingOpenArgs): TomographyPickingOpenData

	@KVBindingRoute("node/${TomographyPickingOpenNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyPickingOpenArgs?): TomographyPickingOpenData

	@KVBindingRoute("node/${TomographyPickingOpenNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyPickingOpenData

	@KVBindingRoute("node/${TomographyPickingOpenNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyPickingOpenArgs(
	val values: ArgValuesToml
) {

	fun particlesList(args: Args, jobId: String): ParticlesList? =
		values.toArgValues(args)
			.tomoSrfMethodOrDefault
			.particlesList(jobId)
}

@Serializable
data class TomographyPickingOpenData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyPickingOpenArgs>,
	val imageUrl: String,
	val numParticles: Long
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
