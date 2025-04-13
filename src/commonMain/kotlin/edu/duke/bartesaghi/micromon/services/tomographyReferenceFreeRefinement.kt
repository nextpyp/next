package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyReferenceFreeRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyReferenceFreeRefinementService {

	@KVBindingRoute("node/${TomographyReferenceFreeRefinementNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inMovieRefinement: CommonJobData.DataId, args: TomographyReferenceFreeRefinementArgs): TomographyReferenceFreeRefinementData

	@KVBindingRoute("node/${TomographyReferenceFreeRefinementNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyReferenceFreeRefinementArgs?): TomographyReferenceFreeRefinementData

	@KVBindingRoute("node/${TomographyReferenceFreeRefinementNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyReferenceFreeRefinementData

	@KVBindingRoute("node/${TomographyReferenceFreeRefinementNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyReferenceFreeRefinementArgs(
	val values: ArgValuesToml,
	val filter: String?
)

@Serializable
data class TomographyReferenceFreeRefinementData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyReferenceFreeRefinementArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
