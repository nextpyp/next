package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyImportDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyImportDataService {

	@KVBindingRoute("node/${TomographyImportDataNodeConfig.ID}/import")
	suspend fun import(userId: String, projectId: String, args: TomographyImportDataArgs): TomographyImportDataData

	@KVBindingRoute("node/${TomographyImportDataNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyImportDataArgs?): TomographyImportDataData

	@KVBindingRoute("node/${TomographyImportDataNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyImportDataData

	@KVBindingRoute("node/${TomographyImportDataNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
class TomographyImportDataData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyImportDataArgs>,
	val imageUrl: String,
	val numTiltSeries: Long,
	val numParticles: Long
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}

@Serializable
data class TomographyImportDataArgs(
	val values: ArgValuesToml
	// NOTE: kvision's forms require a default value here, otherwise the deserializer will throw an error
)