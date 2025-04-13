package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyNewCoarseRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyNewCoarseRefinementService {

	@KVBindingRoute("node/${TomographyNewCoarseRefinementNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inMovieRefinement: CommonJobData.DataId, args: TomographyNewCoarseRefinementArgs): TomographyNewCoarseRefinementData

	@KVBindingRoute("node/${TomographyNewCoarseRefinementNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyNewCoarseRefinementArgs?): TomographyNewCoarseRefinementData

	@KVBindingRoute("node/${TomographyNewCoarseRefinementNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyNewCoarseRefinementData

	@KVBindingRoute("node/${TomographyNewCoarseRefinementNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyNewCoarseRefinementArgs(
	val values: ArgValuesToml,
	val filter: String?
)

@Serializable
data class TomographyNewCoarseRefinementData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyNewCoarseRefinementArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
