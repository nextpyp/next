package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyFlexibleRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyFlexibleRefinementService {

	@KVBindingRoute("node/${TomographyFlexibleRefinementNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inMovieRefinements: CommonJobData.DataId, args: TomographyFlexibleRefinementArgs): TomographyFlexibleRefinementData

	@KVBindingRoute("node/${TomographyFlexibleRefinementNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyFlexibleRefinementArgs?): TomographyFlexibleRefinementData

	@KVBindingRoute("node/${TomographyFlexibleRefinementNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyFlexibleRefinementData

	@KVBindingRoute("node/${TomographyFlexibleRefinementNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
data class TomographyFlexibleRefinementArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyFlexibleRefinementData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyFlexibleRefinementArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
}
