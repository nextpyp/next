package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyReferenceBasedRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyReferenceBasedRefinementService {

	@KVBindingRoute("node/${TomographyReferenceBasedRefinementNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inMovieRefinement: CommonJobData.DataId, args: TomographyReferenceBasedRefinementArgs): TomographyReferenceBasedRefinementData

	@KVBindingRoute("node/${TomographyReferenceBasedRefinementNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyReferenceBasedRefinementArgs?): TomographyReferenceBasedRefinementData

	@KVBindingRoute("node/${TomographyReferenceBasedRefinementNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyReferenceBasedRefinementData

	@KVBindingRoute("node/${TomographyReferenceBasedRefinementNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyReferenceBasedRefinementArgs(
	val values: ArgValuesToml,
	val filter: String?
)

@Serializable
data class TomographyReferenceBasedRefinementData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyReferenceBasedRefinementArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
