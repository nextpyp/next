package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyInitialRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyInitialRefinementService {

	@KVBindingRoute("node/${TomographyInitialRefinementNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inMovieRefinement: CommonJobData.DataId, args: TomographyInitialRefinementArgs): TomographyInitialRefinementData

	@KVBindingRoute("node/${TomographyInitialRefinementNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyInitialRefinementArgs?): TomographyInitialRefinementData

	@KVBindingRoute("node/${TomographyInitialRefinementNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyInitialRefinementData

	@KVBindingRoute("node/${TomographyInitialRefinementNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyInitialRefinementArgs(
	val values: ArgValuesToml,
	val filter: String?
)

@Serializable
data class TomographyInitialRefinementData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyInitialRefinementArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
