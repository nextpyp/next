package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyMovieCleaningNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyMovieCleaningService {

	@KVBindingRoute("node/${TomographyMovieCleaningNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inMovieRefinements: CommonJobData.DataId, args: TomographyMovieCleaningArgs): TomographyMovieCleaningData

	@KVBindingRoute("node/${TomographyMovieCleaningNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyMovieCleaningArgs?): TomographyMovieCleaningData

	@KVBindingRoute("node/${TomographyMovieCleaningNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyMovieCleaningData

	@KVBindingRoute("node/${TomographyMovieCleaningNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
data class TomographyMovieCleaningArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyMovieCleaningData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyMovieCleaningArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
