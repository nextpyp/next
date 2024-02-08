package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticleSessionDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticleSessionDataService {

	@KVBindingRoute("node/${SingleParticleSessionDataNodeConfig.ID}/import")
	suspend fun import(userId: String, projectId: String, sessionId: String): SingleParticleSessionDataData

	@KVBindingRoute("node/${SingleParticleSessionDataNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticleSessionDataArgs?): SingleParticleSessionDataData

	@KVBindingRoute("node/${SingleParticleSessionDataNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticleSessionDataData

	@KVBindingRoute("node/${SingleParticleSessionDataNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
class SingleParticleSessionDataData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticleSessionDataArgs>,
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
data class SingleParticleSessionDataArgs(
	val sessionId: String,
	val values: ArgValuesToml,
	val particlesName: String? = null
	// NOTE: kvision's forms require a default value here, otherwise the deserializer will throw an error
)
