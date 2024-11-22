package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyMiloEvalNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyMiloEvalService {

	@KVBindingRoute("node/${TomographyMiloEvalNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyMiloEvalArgs): TomographyMiloEvalData

	@KVBindingRoute("node/${TomographyMiloEvalNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyMiloEvalArgs?): TomographyMiloEvalData

	@KVBindingRoute("node/${TomographyMiloEvalNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyMiloEvalData

	@KVBindingRoute("node/${TomographyMiloEvalNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */

	@KVBindingRoute("node/${TomographyMiloEvalNodeConfig.ID}/data")
	suspend fun data(jobId: String): Option<FileDownloadData>


	companion object {

		fun results2dPath(jobId: String, size: ImageSize): String =
			"/kv/node/${TomographyMiloEvalNodeConfig.ID}/$jobId/results_2d/${size.id}"

		fun results2dLabelsPath(jobId: String, size: ImageSize): String =
			"/kv/node/${TomographyMiloEvalNodeConfig.ID}/$jobId/results_2d_labels/${size.id}"

		fun results3dPath(jobId: String, size: ImageSize): String =
			"/kv/node/${TomographyMiloEvalNodeConfig.ID}/$jobId/results_3d/${size.id}"

		fun dataPath(jobId: String): String =
			"/kv/node/${TomographyMiloEvalNodeConfig.ID}/$jobId/data"

		fun uploadPath(jobId: String): String =
			"/kv/node/${TomographyMiloEvalNodeConfig.ID}/$jobId/upload_particles"
	}
}


@Serializable
data class TomographyMiloEvalArgs(
	val values: ArgValuesToml,
	val filter: String?
)

@Serializable
data class TomographyMiloEvalData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyMiloEvalArgs>,
	val imageUrl: String,
	val numParticles: Long
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
