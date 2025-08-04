package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.SingleParticleDrgnNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ISingleParticleDrgnService {

	@KVBindingRoute("node/${SingleParticleDrgnNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: SingleParticleDrgnArgs): SingleParticleDrgnData

	@KVBindingRoute("node/${SingleParticleDrgnNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: SingleParticleDrgnArgs?): SingleParticleDrgnData

	@KVBindingRoute("node/${SingleParticleDrgnNodeConfig.ID}/get")
	suspend fun get(jobId: String): SingleParticleDrgnData

	@KVBindingRoute("node/${SingleParticleDrgnNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */

	@KVBindingRoute("node/${SingleParticleDrgnNodeConfig.ID}/getParams")
	suspend fun getParams(jobId: String): Option<SingleParticleDrgnParams>

	@KVBindingRoute("node/${SingleParticleDrgnNodeConfig.ID}/umap/classMrcData")
	suspend fun classMrcDataUmap(jobId: String, classNum: Int): Option<FileDownloadData>

	@KVBindingRoute("node/${SingleParticleDrgnNodeConfig.ID}/pca/classMrcData")
	suspend fun classMrcDataPca(jobId: String, dim: Int, classNum: Int): Option<FileDownloadData>

	companion object {

		fun plotUmapScatterSubplotkmeanslabel(jobId: String) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/plot/learning_curve_epoch"

		fun plotUmapHexbin(jobId: String) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/plot/umap_hex"

		fun plotUmapHex(jobId: String) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/plot/umap_hexbin"

		fun plotUmapMarginals(jobId: String) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/plot/umap_marginals"

		fun plotUmapTraversal(jobId: String) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/plot/umap_traversal"

		fun plotUmapTraversalConnected(jobId: String) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/plot/umap_traversal_connected"

		fun plotUmap(jobId: String) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/plot/umap"

		fun plotPcaHexbin(jobId: String) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/plot/z_pca_hex"

		fun plotPcaMarginals(jobId: String) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/plot/z_pca_marginals"

		fun plotPcaTraversal(jobId: String) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/plot/pca_traversal"

		fun plotPcaHexbinTraversal(jobId: String) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/plot/pca_traversal_hex"

		fun plotPca(jobId: String) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/plot/z_pca"

		fun classImagePathUmap(jobId: String, classNum: Int, size: ImageSize) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/class/$classNum/image/${size.id}"

		fun classMrcPathUmap(jobId: String, classNum: Int) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/class/$classNum/mrc"

		fun plotUmapColorlatentpca(jobId: String, dim: Int) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/dim/$dim/plot/umap_colorlatentpca"

		fun classImagePathPca(jobId: String, dim: Int, classNum: Int, size: ImageSize) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/dim/$dim/class/$classNum/image/${size.id}"

		fun classMrcPathPca(jobId: String, dim: Int, classNum: Int) =
			"/kv/node/${SingleParticleDrgnNodeConfig.ID}/$jobId/dim/$dim/class/$classNum/mrc"
	}
}


@Serializable
data class SingleParticleDrgnArgs(
	val values: ArgValuesToml
)

@Serializable
data class SingleParticleDrgnData(
	override val common: CommonJobData,
	val args: JobArgs<SingleParticleDrgnArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}

@Serializable
data class SingleParticleDrgnParams(
	val skipumap: Boolean,
	val pc: Int,
	val ksample: Int,
	val epoch: Int
) {

	// alias some params so they make more sense in this context
	val numDimensions: Int get() = pc
	val numClasses: Int get() = ksample
	val numEpoch: Int get() = epoch
}
