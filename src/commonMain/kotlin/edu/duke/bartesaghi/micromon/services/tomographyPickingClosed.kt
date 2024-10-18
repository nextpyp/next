package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyPickingClosedNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.toArgValues
import edu.duke.bartesaghi.micromon.pyp.tomoSrfMethodOrDefault
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyPickingClosedService {

	@KVBindingRoute("node/${TomographyPickingClosedNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyPickingClosedArgs): TomographyPickingClosedData

	@KVBindingRoute("node/${TomographyPickingClosedNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyPickingClosedArgs?): TomographyPickingClosedData

	@KVBindingRoute("node/${TomographyPickingClosedNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyPickingClosedData

	@KVBindingRoute("node/${TomographyPickingClosedNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
data class TomographyPickingClosedArgs(
	val values: ArgValuesToml
) {

	fun particlesList(args: Args, jobId: String): ParticlesList? =
		values.toArgValues(args)
			.tomoSrfMethodOrDefault
			.particlesList(jobId)
}

@Serializable
data class TomographyPickingClosedData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyPickingClosedArgs>,
	val imageUrl: String,
	val numParticles: Long
) : JobData {
	override fun isChanged() = args.hasNext()
}
