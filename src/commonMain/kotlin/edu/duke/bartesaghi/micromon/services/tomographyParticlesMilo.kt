package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyParticlesMiloNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyParticlesMiloService {

	@KVBindingRoute("node/${TomographyParticlesMiloNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyParticlesMiloArgs): TomographyParticlesMiloData

	@KVBindingRoute("node/${TomographyParticlesMiloNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyParticlesMiloArgs?): TomographyParticlesMiloData

	@KVBindingRoute("node/${TomographyParticlesMiloNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyParticlesMiloData

	@KVBindingRoute("node/${TomographyParticlesMiloNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyParticlesMiloArgs(
	val values: ArgValuesToml,
	val filter: String?
)

@Serializable
data class TomographyParticlesMiloData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyParticlesMiloArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
}
