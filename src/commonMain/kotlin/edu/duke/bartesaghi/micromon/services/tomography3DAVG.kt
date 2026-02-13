package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.Tomography3DAVGNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomography3DAVGService {

	@KVBindingRoute("node/${Tomography3DAVGNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: Tomography3DAVGArgs): Tomography3DAVGData

	@KVBindingRoute("node/${Tomography3DAVGNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: Tomography3DAVGArgs?): Tomography3DAVGData

	@KVBindingRoute("node/${Tomography3DAVGNodeConfig.ID}/get")
	suspend fun get(jobId: String): Tomography3DAVGData

	@KVBindingRoute("node/${Tomography3DAVGNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */

	@KVBindingRoute("node/${Tomography3DAVGNodeConfig.ID}/getParams")
	suspend fun getParams(jobId: String): Option<Tomography3DAVGParams>

	@KVBindingRoute("node/${Tomography3DAVGNodeConfig.ID}/pca/classMrcData")
	suspend fun classMrcData(jobId: String, iter: Int, classNum: Int): Option<FileDownloadData>

	@KVBindingRoute("node/${Tomography3DAVGNodeConfig.ID}/pca/refinedClassMrcData")
	suspend fun refinedClassMrcData(jobId: String, iter: Int, classNum: Int): Option<FileDownloadData>

	@KVBindingRoute("node/${Tomography3DAVGNodeConfig.ID}average/averageMrcData")
	suspend fun averageMrcData(jobId: String, iter: Int): Option<FileDownloadData>

	@KVBindingRoute("node/${Tomography3DAVGNodeConfig.ID}average/averageSymmetrizedMrcData")
	suspend fun averageSymmetrizedMrcData(jobId: String, iter: Int): Option<FileDownloadData>

	@KVBindingRoute("node/${Tomography3DAVGNodeConfig.ID}average/testMetricFilterMrcData")
	suspend fun testMetricFilterMrcData(jobId: String): Option<FileDownloadData>

	@KVBindingRoute("node/${Tomography3DAVGNodeConfig.ID}average/referencesMrcData")
	suspend fun referencesMrcData(jobId: String, iter: Int): Option<FileDownloadData>

	@KVBindingRoute("node/${Tomography3DAVGNodeConfig.ID}average/referencesFilteredMrcData")
	suspend fun referencesFilteredMrcData(jobId: String, iter: Int): Option<FileDownloadData>

	companion object {

		fun classMrcPath(jobId: String, iter: Int, classNum: Int) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/iter/$iter/class/$classNum/mrc"

		fun refinedClassMrcPath(jobId: String, iter: Int, classNum: Int) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/iter/$iter/refinedclass/$classNum/mrc"

		fun representativeClassImagePath(jobId: String, iter: Int, size: ImageSize) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/iter/$iter/image/${size.id}"

		fun classImagePath(jobId: String, iter: Int, classNum: Int, size: ImageSize) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/iter/$iter/class/$classNum/image/${size.id}"

		fun averageImagePath(jobId: String, iter: Int, size: ImageSize) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/iter/$iter/image/${size.id}"

		fun averageSymmetrizedImagePath(jobId: String, iter: Int, size: ImageSize) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/iter/$iter/imagesym/${size.id}"

		fun testMetricFilterImagePath(jobId: String, size: ImageSize) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/testmetricfilter/${size.id}"

		fun averageMrcPath(jobId: String, iter: Int) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/iter/$iter/mrc"

		fun referencesImagePath(jobId: String, iter: Int, size: ImageSize) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/iter/$iter/references/${size.id}"

		fun referencesFilteredImagePath(jobId: String, iter: Int, size: ImageSize) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/iter/$iter/referencesFiltered/${size.id}"

		fun referencesMrcPath(jobId: String, iter: Int) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/iter/$iter/references/mrc"

		fun referencesFilteredMrcPath(jobId: String, iter: Int) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/iter/$iter/referencesFiltered/mrc"

		fun averageSymmetrizedMrcPath(jobId: String, iter: Int) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/itersym/$iter/mrc"

		fun testMetricFilterMrcPath(jobId: String) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/filtered/mrc"

		fun refinedClassImagePath(jobId: String, iter: Int, classNum: Int, size: ImageSize) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/iter/$iter/refinedclass/$classNum/image/${size.id}"

		fun alignedClassImagePath(jobId: String, iter: Int, classNum: Int, size: ImageSize) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/iter/$iter/class/$classNum/image/${size.id}"

		fun classMrcPathPca(jobId: String, dim: Int, classNum: Int) =
			"/kv/node/${Tomography3DAVGNodeConfig.ID}/$jobId/dim/$dim/class/$classNum/mrc"

	}
}


@Serializable
data class Tomography3DAVGArgs(
	val values: ArgValuesToml
)

@Serializable
data class Tomography3DAVGData(
	override val common: CommonJobData,
	val args: JobArgs<Tomography3DAVGArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}

@Serializable
data class Tomography3DAVGParams(
	val classes: Int,
	val iterations: Int,
	val centeringIterations: Int,
	val applySymmetry: Boolean
) {

	// alias some params so they make more sense in this context
	val numIterations: Int get() = iterations
	val numCenteringIterations: Int get() = centeringIterations
	val numClasses: Int get() = classes
	val symmetryApplied: Boolean get() = applySymmetry
}
