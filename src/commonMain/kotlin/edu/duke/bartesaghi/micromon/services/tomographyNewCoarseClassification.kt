package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyNewCoarseClassificationNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyNewCoarseClassificationService {

	@KVBindingRoute("node/${TomographyNewCoarseClassificationNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inMovieRefinement: CommonJobData.DataId, args: TomographyNewCoarseClassificationArgs): TomographyNewCoarseClassificationData

	@KVBindingRoute("node/${TomographyNewCoarseClassificationNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyNewCoarseClassificationArgs?): TomographyNewCoarseClassificationData

	@KVBindingRoute("node/${TomographyNewCoarseClassificationNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyNewCoarseClassificationData

	@KVBindingRoute("node/${TomographyNewCoarseClassificationNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */
}


@Serializable
data class TomographyNewCoarseClassificationArgs(
	val values: ArgValuesToml,
	val filter: String?
)

@Serializable
data class TomographyNewCoarseClassificationData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyNewCoarseClassificationArgs>,
	val imageURL: String,
	val jobInfoString: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
