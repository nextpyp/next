package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticleRelionDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.services.JobArgs.Companion.newest
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticleRelionDataService {

	@KVBindingRoute("node/${SingleParticleRelionDataNodeConfig.ID}/import")
	suspend fun import(userId: String, projectId: String, args: SingleParticleRelionDataArgs): SingleParticleRelionDataData

	@KVBindingRoute("node/${SingleParticleRelionDataNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticleRelionDataArgs?): SingleParticleRelionDataData

	@KVBindingRoute("node/${SingleParticleRelionDataNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticleRelionDataData

	@KVBindingRoute("node/${SingleParticleRelionDataNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
class SingleParticleRelionDataData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticleRelionDataArgs>,
	val display: JobArgs<SingleParticleRelionDataDisplay>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()

	fun newestArgsAndDisplay() = (args to display).newest()
}

/**
 * User-supplied arguments for the job.
 */
@Serializable
data class SingleParticleRelionDataArgs(
	val values: ArgValuesToml,
	val particlesName: String? = null
	// NOTE: kvision's forms require a default value here, otherwise the deserializer will throw an error
)

/**
 * Info computed by the server, derived from the arguments.
 */
@Serializable
data class SingleParticleRelionDataDisplay(
	val numMovies: Int?
)
