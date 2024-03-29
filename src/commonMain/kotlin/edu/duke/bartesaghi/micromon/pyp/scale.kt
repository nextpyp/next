package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.services.ExportServiceProperty
import kotlinx.serialization.Serializable


/**
 * A way to represent the physical size of things in micrograph and tomogram images.
 * These are params common to a set of source images.
 */
@Serializable
data class ImagesScale(
	/** The size, in Angstroms, of one pixel in the source image */
	val pixelA: Double,
	/** The radius, in Angstroms, of a particle */
	val particleRadiusA: Double
) {

	companion object {

		fun default() = ImagesScale(1.0, 65.0)
	}

	/** The particle radius, in unbinned pixels */
	@ExportServiceProperty(skip=true)
	val particleRadiusUnbinned: Double =
		particleRadiusA.aToUnbinned(this)
}

fun Double.unbinnedToA(scale: ImagesScale): Double = this*scale.pixelA
fun Double.aToUnbinned(scale: ImagesScale): Double = this/scale.pixelA


@Serializable
data class ImageDims(
	/** image width, in pixels */
	val width: Int,
	/** image height, in pixels */
	val height: Int,
	/** image depth, in pixels (for tomograms) */
	val depth: Int,
	/** the binning factor, same for all dimensions */
	val binningFactor: Int
) {

	companion object {

		/**
		 * Slicing applies an extra binning factor of 2,
		 * so there are always half as many slices as z-voxels.
		 */
		const val SLICE_FACTOR = 2
	}

	@ExportServiceProperty(skip=true)
	val numSlices: Int get() =
		depth/binningFactor/SLICE_FACTOR
}
