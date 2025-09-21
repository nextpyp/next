package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyRelionRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyRelionRefinementService {

	@KVBindingRoute("node/${TomographyRelionRefinementNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inMovieRefinement: CommonJobData.DataId, args: TomographyRelionRefinementArgs): TomographyRelionRefinementData

	@KVBindingRoute("node/${TomographyRelionRefinementNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyRelionRefinementArgs?): TomographyRelionRefinementData

	@KVBindingRoute("node/${TomographyRelionRefinementNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyRelionRefinementData

	@KVBindingRoute("node/${TomographyRelionRefinementNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyRelionRefinementArgs(
	val values: ArgValuesToml,
	val filter: String?
)

@Serializable
data class TomographyRelionRefinementData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyRelionRefinementArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
