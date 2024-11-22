package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticleRawDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.services.JobArgs.Companion.newest
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticleRawDataService {

	@KVBindingRoute("node/${SingleParticleRawDataNodeConfig.ID}/import")
	suspend fun import(userId: String, projectId: String, args: SingleParticleRawDataArgs): SingleParticleRawDataData

	@KVBindingRoute("node/${SingleParticleRawDataNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticleRawDataArgs?): SingleParticleRawDataData

	@KVBindingRoute("node/${SingleParticleRawDataNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticleRawDataData

	@KVBindingRoute("node/${SingleParticleRawDataNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class SingleParticleRawDataData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticleRawDataArgs>,
	val display: JobArgs<SingleParticleRawDataDisplay>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values

	fun newestArgsAndDisplay() = (args to display).newest()
}

/**
 * User-supplied arguments for the job.
 */
@Serializable
data class SingleParticleRawDataArgs(
	val values: ArgValuesToml
)

/**
 * Info computed by the server, derived from the arguments.
 */
@Serializable
data class SingleParticleRawDataDisplay(
	val numMovies: Int?
)
