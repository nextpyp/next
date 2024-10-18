package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyRawDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.services.JobArgs.Companion.newest
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyRawDataService {

	@KVBindingRoute("node/${TomographyRawDataNodeConfig.ID}/import")
	suspend fun import(userId: String, projectId: String, args: TomographyRawDataArgs): TomographyRawDataData

	@KVBindingRoute("node/${TomographyRawDataNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyRawDataArgs?): TomographyRawDataData

	@KVBindingRoute("node/${TomographyRawDataNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyRawDataData

	@KVBindingRoute("node/${TomographyRawDataNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
class TomographyRawDataData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyRawDataArgs>,
	val display: JobArgs<TomographyRawDataDisplay>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()

	fun newestArgsAndDisplay() = (args to display).newest()
}

@Serializable
data class TomographyRawDataArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyRawDataDisplay(
	val numTiltSeries: Int?
)
