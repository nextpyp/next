package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyPickingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.toArgValues
import edu.duke.bartesaghi.micromon.pyp.tomoPickMethodOrDefault
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyPickingService {

	@KVBindingRoute("node/${TomographyPickingNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyPickingArgs, copyArgs: TomographyPickingCopyArgs?): TomographyPickingData

	@KVBindingRoute("node/${TomographyPickingNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyPickingArgs?): TomographyPickingData

	@KVBindingRoute("node/${TomographyPickingNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyPickingData

	@KVBindingRoute("node/${TomographyPickingNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
data class TomographyPickingArgs(
	val values: ArgValuesToml,
	val filter: String?,
	val particlesName: String?
) {

	fun particlesList(args: Args, jobId: String): ParticlesList? =
		values.toArgValues(args)
			.tomoPickMethodOrDefault
			.particlesList(jobId)
}

@Serializable
data class TomographyPickingData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyPickingArgs>,
	val imageUrl: String,
	val numParticles: Long
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}


@Serializable
data class TomographyPickingCopyArgs(
	override val copyFromJobId: String,
	val copyData: Boolean,
	val copyParticlesToManual: Boolean
) : JobCopyArgs
