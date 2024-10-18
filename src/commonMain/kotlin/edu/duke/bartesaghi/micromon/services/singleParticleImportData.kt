package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticleImportDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticleImportDataService {

	@KVBindingRoute("node/${SingleParticleImportDataNodeConfig.ID}/import")
	suspend fun import(userId: String, projectId: String, args: SingleParticleImportDataArgs): SingleParticleImportDataData

	@KVBindingRoute("node/${SingleParticleImportDataNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticleImportDataArgs?): SingleParticleImportDataData

	@KVBindingRoute("node/${SingleParticleImportDataNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticleImportDataData

	@KVBindingRoute("node/${SingleParticleImportDataNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
class SingleParticleImportDataData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticleImportDataArgs>,
	val imageUrl: String,
	val numMicrographs: Long,
	val numParticles: Long
) : JobData {
	override fun isChanged() = args.hasNext()
}

/**
 * User-supplied arguments for the job.
 */
@Serializable
data class SingleParticleImportDataArgs(
	val values: ArgValuesToml
	// NOTE: kvision's forms require a default value here, otherwise the deserializer will throw an error
)