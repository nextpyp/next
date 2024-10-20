package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyImportDataPureNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyImportDataPureService {

	@KVBindingRoute("node/${TomographyImportDataPureNodeConfig.ID}/import")
	suspend fun import(userId: String, projectId: String, args: TomographyImportDataPureArgs): TomographyImportDataPureData

	@KVBindingRoute("node/${TomographyImportDataPureNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyImportDataPureArgs?): TomographyImportDataPureData

	@KVBindingRoute("node/${TomographyImportDataPureNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyImportDataPureData

	@KVBindingRoute("node/${TomographyImportDataPureNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
class TomographyImportDataPureData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyImportDataPureArgs>,
	val imageUrl: String,
	val numTiltSeries: Long,
	val numParticles: Long
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}

@Serializable
data class TomographyImportDataPureArgs(
	val values: ArgValuesToml
	// NOTE: kvision's forms require a default value here, otherwise the deserializer will throw an error
)