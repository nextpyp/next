package edu.duke.bartesaghi.micromon.pyp

import io.kvision.utils.perc


/**
 * A tool to translate between different coordinate systems used in micrograph and tomogram processing
 */
class Scaler(
	val scale: ImagesScale,
	val sourceDims: ImageDims
) {

	companion object {

		fun of(scale: ImagesScale?, sourceDims: ImageDims?): Scaler? {
			return Scaler(
				scale ?: return null,
				sourceDims ?: return null
			)
		}

		// we always use this many tomogram slices, right?
		// NOTE: When "extra z binning = 2" is mentioned,
		// that just means tomograms are typically 256 voxels deep,
		// but we only show 128 slices in the UI.
		// Don't actually apply any extra binning factors to z coordinate transformations.
		// We account for this extra binning factor already by normalizing the slice index.
		const val NUM_SLICES = 128
	}
}


fun Double.unbinnedToNormalizedX(scaler: Scaler): Double = this/scaler.sourceDims.width
fun Double.unbinnedToNormalizedY(scaler: Scaler): Double = this/scaler.sourceDims.height

fun Double.normalizedToUnbinnedX(scaler: Scaler): Double = this*scaler.sourceDims.width
fun Double.normalizedToUnbinnedY(scaler: Scaler): Double = this*scaler.sourceDims.height
fun Double.normalizedToUnbinnedZ(scaler: Scaler): Double = this*scaler.sourceDims.depth


fun Double.flipNormalized(): Double = 1.0 - this

fun Double.unbinnedToBinned(scaler: Scaler, extraBinning: Int = 1): Double = this/scaler.sourceDims.binningFactor/extraBinning
fun Double.binnedToUnbinned(scaler: Scaler, extraBinning: Int = 1): Double = this*scaler.sourceDims.binningFactor*extraBinning

fun Double.binnedToNormalizedX(scaler: Scaler): Double = binnedToUnbinned(scaler).unbinnedToNormalizedX(scaler)
fun Double.binnedToNormalizedY(scaler: Scaler): Double = binnedToUnbinned(scaler).unbinnedToNormalizedY(scaler)

fun Double.normalizedToBinnedX(scaler: Scaler): Double = normalizedToUnbinnedX(scaler).unbinnedToBinned(scaler)
fun Double.normalizedToBinnedY(scaler: Scaler): Double = normalizedToUnbinnedY(scaler).unbinnedToBinned(scaler)
fun Double.normalizedToBinnedZ(scaler: Scaler): Double = normalizedToUnbinnedZ(scaler).unbinnedToBinned(scaler)

fun Int.sliceToNormalizedZ(): Double = toDouble()/(Scaler.NUM_SLICES.toDouble() - 1)
fun Int.sliceToBinnedZ(scaler: Scaler): Double = sliceToNormalizedZ().normalizedToBinnedZ(scaler)

fun Double.normalizedToPercent() = (this*100).perc

fun Double.unbinnedToA(scaler: Scaler): Double = this.unbinnedToA(scaler.scale)
fun Double.aToUnbinned(scaler: Scaler): Double = this.aToUnbinned(scaler.scale)
