package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyCoarseRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyCoarseRefinementService {

	@KVBindingRoute("node/${TomographyCoarseRefinementNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inMovieRefinement: CommonJobData.DataId, args: TomographyCoarseRefinementArgs): TomographyCoarseRefinementData

	@KVBindingRoute("node/${TomographyCoarseRefinementNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyCoarseRefinementArgs?): TomographyCoarseRefinementData

	@KVBindingRoute("node/${TomographyCoarseRefinementNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyCoarseRefinementData

	@KVBindingRoute("node/${TomographyCoarseRefinementNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyCoarseRefinementArgs(
	val values: ArgValuesToml,
	val filter: String?
)

@Serializable
data class TomographyCoarseRefinementData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyCoarseRefinementArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
}
