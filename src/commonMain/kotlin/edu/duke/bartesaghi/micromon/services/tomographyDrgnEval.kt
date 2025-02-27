package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyDrgnEvalNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyDrgnEvalService {

	@KVBindingRoute("node/${TomographyDrgnEvalNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyDrgnEvalArgs): TomographyDrgnEvalData

	@KVBindingRoute("node/${TomographyDrgnEvalNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyDrgnEvalArgs?): TomographyDrgnEvalData

	@KVBindingRoute("node/${TomographyDrgnEvalNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyDrgnEvalData

	@KVBindingRoute("node/${TomographyDrgnEvalNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */

	@KVBindingRoute("node/${TomographyDrgnEvalNodeConfig.ID}/getParams")
	suspend fun getParams(jobId: String): Option<TomographyDrgnEvalParams>

	@KVBindingRoute("node/${TomographyDrgnEvalNodeConfig.ID}/umap/classMrcData")
	suspend fun classMrcDataUmap(jobId: String, classNum: Int): Option<FileDownloadData>

	@KVBindingRoute("node/${TomographyDrgnEvalNodeConfig.ID}/pca/classMrcData")
	suspend fun classMrcDataPca(jobId: String, dim: Int, classNum: Int): Option<FileDownloadData>


	companion object {

		fun classImagePathUmap(jobId: String, classNum: Int, size: ImageSize) =
			"/kv/node/${TomographyDrgnEvalNodeConfig.ID}/$jobId/umap/class/$classNum/image/${size.id}"

		fun classMrcPathUmap(jobId: String, classNum: Int) =
			"/kv/node/${TomographyDrgnEvalNodeConfig.ID}/$jobId/umap/class/$classNum/mrc"

		fun plotResolutionPathUmap(jobId: String) =
			"/kv/node/${TomographyDrgnEvalNodeConfig.ID}/$jobId/umap/plot/resolution"

		fun plotOccupancyPathUmap(jobId: String) =
			"/kv/node/${TomographyDrgnEvalNodeConfig.ID}/$jobId/umap/plot/occupancy"

		fun classImagePathPca(jobId: String, dim: Int, classNum: Int, size: ImageSize) =
			"/kv/node/${TomographyDrgnEvalNodeConfig.ID}/$jobId/pca/dim/$dim/class/$classNum/image/${size.id}"

		fun classMrcPathPca(jobId: String, dim: Int, classNum: Int) =
			"/kv/node/${TomographyDrgnEvalNodeConfig.ID}/$jobId/pca/dim/$dim/class/$classNum/mrc"

		fun plotResolutionPathPca(jobId: String) =
			"/kv/node/${TomographyDrgnEvalNodeConfig.ID}/$jobId/pca/plot/resolution"

		fun plotOccupancyPathPca(jobId: String) =
			"/kv/node/${TomographyDrgnEvalNodeConfig.ID}/$jobId/pca/plot/occupancy"
	}
}


@Serializable
data class TomographyDrgnEvalArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyDrgnEvalData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyDrgnEvalArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}


@Serializable
data class TomographyDrgnEvalParams(
	val skipumap: Boolean,
	val pc: Int,
	val ksample: Int
) {
	fun mode(): TomographyDrgnEvalMode =
		TomographyDrgnEvalMode.from(this)
}


sealed interface TomographyDrgnEvalMode {

	companion object {

		fun from(params: TomographyDrgnEvalParams): TomographyDrgnEvalMode =
			if (params.skipumap) {
				PCA(
					numDimensions = params.pc,
					numClasses = params.ksample // TODO: is this right?
				)
			} else {
				UMAP(
					numClasses = params.ksample // TODO: is this right?
				)
			}
	}

	data class UMAP(
		val numClasses: Int
	) : TomographyDrgnEvalMode

	data class PCA(
		val numDimensions: Int,
		val numClasses: Int
	) : TomographyDrgnEvalMode
}
