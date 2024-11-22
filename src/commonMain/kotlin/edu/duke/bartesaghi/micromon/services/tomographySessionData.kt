package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographySessionDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographySessionDataService {

	@KVBindingRoute("node/${TomographySessionDataNodeConfig.ID}/import")
	suspend fun import(userId: String, projectId: String, sessionId: String): TomographySessionDataData

	@KVBindingRoute("node/${TomographySessionDataNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographySessionDataArgs?): TomographySessionDataData

	@KVBindingRoute("node/${TomographySessionDataNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographySessionDataData

	@KVBindingRoute("node/${TomographySessionDataNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
class TomographySessionDataData(
	override val common: CommonJobData,
	val args: JobArgs<TomographySessionDataArgs>,
	val imageUrl: String,
	val numTiltSeries: Long,
	val numParticles: Long
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}

/**
 * User-supplied arguments for the job.
 */
@Serializable
data class TomographySessionDataArgs(
	val sessionId: String,
	val values: ArgValuesToml,
	val particlesName: String? = null
	// NOTE: kvision's forms require a default value here, otherwise the deserializer will throw an error
)
