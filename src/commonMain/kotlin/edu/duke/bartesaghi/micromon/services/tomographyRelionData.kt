package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyRelionDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.services.JobArgs.Companion.newest
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyRelionDataService {

	@KVBindingRoute("node/${TomographyRelionDataNodeConfig.ID}/import")
	suspend fun import(userId: String, projectId: String, args: TomographyRelionDataArgs): TomographyRelionDataData

	@KVBindingRoute("node/${TomographyRelionDataNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyRelionDataArgs?): TomographyRelionDataData

	@KVBindingRoute("node/${TomographyRelionDataNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyRelionDataData

	@KVBindingRoute("node/${TomographyRelionDataNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
class TomographyRelionDataData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyRelionDataArgs>,
	val display: JobArgs<TomographyRelionDataDisplay>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()

	fun newestArgsAndDisplay() = (args to display).newest()
}

@Serializable
data class TomographyRelionDataArgs(
	val values: ArgValuesToml,
	val particlesName: String? = null
	// NOTE: kvision's forms require a default value here, otherwise the deserializer will throw an error
)

@Serializable
data class TomographyRelionDataDisplay(
	val numTiltSeries: Int?
)
