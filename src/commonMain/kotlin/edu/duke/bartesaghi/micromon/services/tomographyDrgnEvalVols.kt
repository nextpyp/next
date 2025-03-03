package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyDrgnEvalVolsNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyDrgnEvalVolsService {

	@KVBindingRoute("node/${TomographyDrgnEvalVolsNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyDrgnEvalVolsArgs): TomographyDrgnEvalVolsData

	@KVBindingRoute("node/${TomographyDrgnEvalVolsNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyDrgnEvalVolsArgs?): TomographyDrgnEvalVolsData

	@KVBindingRoute("node/${TomographyDrgnEvalVolsNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyDrgnEvalVolsData

	@KVBindingRoute("node/${TomographyDrgnEvalVolsNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */

	@KVBindingRoute("node/${TomographyDrgnEvalVolsNodeConfig.ID}/getParams")
	suspend fun getParams(jobId: String): Option<TomographyDrgnEvalVolsParams>

	@KVBindingRoute("node/${TomographyDrgnEvalVolsNodeConfig.ID}/umap/classMrcData")
	suspend fun classMrcDataUmap(jobId: String, classNum: Int): Option<FileDownloadData>

	@KVBindingRoute("node/${TomographyDrgnEvalVolsNodeConfig.ID}/pca/classMrcData")
	suspend fun classMrcDataPca(jobId: String, dim: Int, classNum: Int): Option<FileDownloadData>


	companion object {

		fun plotUmapScatterSubplotkmeanslabel(jobId: String) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/plot/umap_scatter_subplotkmeanslabel"

		fun plotUmapScatterColorkmeanslabel(jobId: String) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/plot/umap_scatter_colorkmeanslabel"

		fun plotUmapScatterAnnotatekmeans(jobId: String) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/plot/umap_scatter_annotatekmeans"

		fun plotUmapHexbinAnnotatekmeans(jobId: String) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/plot/umap_hexbin_annotatekmeans"

		fun plotPcaScatterSubplotkmeanslabel(jobId: String) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/plot/pca_scatter_subplotkmeanslabel"

		fun plotPcaScatterClorkmeanslabel(jobId: String) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/plot/pca_scatter_colorkmeanslabel"

		fun plotPcaScatterColorkmeanslabel(jobId: String) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/plot/pca_scatter_colorkmeanslabel"

		fun plotPcaScatterAnnotatekmeans(jobId: String) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/plot/pca_scatter_annotatekmeans"

		fun plotPcaHexbinAnnotatekmeans(jobId: String) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/plot/pca_hexbin_annotatekmeans"

		fun plotTomogramLabelDistribution(jobId: String) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/plot/tomogram_label_distribution"

		fun plotUmapHexbinAnnotatepca(jobId: String) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/plot/umap_hexbin_annotatepca"

		fun plotUmapScatterAnnotatepca(jobId: String) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/plot/umap_scatter_annotatepca"

		fun plotPcaHexbinAnnotatepca(jobId: String) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/plot/pca_hexbin_annotatepca"

		fun plotPcaScatterAnnotatepca(jobId: String) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/plot/pca_scatter_annotatepca"

		fun classImagePathUmap(jobId: String, classNum: Int, size: ImageSize) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/class/$classNum/image/${size.id}"

		fun classMrcPathUmap(jobId: String, classNum: Int) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/class/$classNum/mrc"

		fun plotUmapColorlatentpca(jobId: String, dim: Int) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/dim/$dim/plot/umap_colorlatentpca"

		fun classImagePathPca(jobId: String, dim: Int, classNum: Int, size: ImageSize) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/dim/$dim/class/$classNum/image/${size.id}"

		fun classMrcPathPca(jobId: String, dim: Int, classNum: Int) =
			"/kv/node/${TomographyDrgnEvalVolsNodeConfig.ID}/$jobId/dim/$dim/class/$classNum/mrc"
	}
}


@Serializable
data class TomographyDrgnEvalVolsArgs(
	val values: ArgValuesToml
)

@Serializable
data class TomographyDrgnEvalVolsData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyDrgnEvalVolsArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}


@Serializable
data class TomographyDrgnEvalVolsParams(
	val skipumap: Boolean,
	val pc: Int,
	val ksample: Int
) {

	// alias some params so they make more sense in this context
	val numDimensions: Int get() = pc
	val numClasses: Int get() = ksample
}
