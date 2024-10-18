package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyFineRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyFineRefinementService {

	@KVBindingRoute("node/${TomographyFineRefinementNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inMovieRefinements: CommonJobData.DataId, args: TomographyFineRefinementArgs): TomographyFineRefinementData

	@KVBindingRoute("node/${TomographyFineRefinementNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyFineRefinementArgs?): TomographyFineRefinementData

	@KVBindingRoute("node/${TomographyFineRefinementNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyFineRefinementData

	@KVBindingRoute("node/${TomographyFineRefinementNodeConfig.ID}/getArgs")
	suspend fun getArgs(includeForwarded: Boolean): String /* Args but serialized */
}


@Serializable
data class TomographyFineRefinementArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyFineRefinementData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyFineRefinementArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
}
