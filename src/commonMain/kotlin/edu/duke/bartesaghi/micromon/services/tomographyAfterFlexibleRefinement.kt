package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyAfterFlexibleRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyAfterFlexibleRefinementService {

	@KVBindingRoute("node/${TomographyAfterFlexibleRefinementNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inMovieAfterRefinements: CommonJobData.DataId, args: TomographyAfterFlexibleRefinementArgs): TomographyAfterFlexibleRefinementData

	@KVBindingRoute("node/${TomographyAfterFlexibleRefinementNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyAfterFlexibleRefinementArgs?): TomographyAfterFlexibleRefinementData

	@KVBindingRoute("node/${TomographyAfterFlexibleRefinementNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyAfterFlexibleRefinementData

	@KVBindingRoute("node/${TomographyAfterFlexibleRefinementNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyAfterFlexibleRefinementArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyAfterFlexibleRefinementData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyAfterFlexibleRefinementArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
