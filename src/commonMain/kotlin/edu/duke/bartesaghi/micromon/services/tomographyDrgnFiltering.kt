package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyDrgnFilteringNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyDrgnFilteringService {

	@KVBindingRoute("node/${TomographyDrgnFilteringNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inMovieRefinements: CommonJobData.DataId, args: TomographyDrgnFilteringArgs): TomographyDrgnFilteringData

	@KVBindingRoute("node/${TomographyDrgnFilteringNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyDrgnFilteringArgs?): TomographyDrgnFilteringData

	@KVBindingRoute("node/${TomographyDrgnFilteringNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyDrgnFilteringData

	@KVBindingRoute("node/${TomographyDrgnFilteringNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyDrgnFilteringArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyDrgnFilteringData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyDrgnFilteringArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
