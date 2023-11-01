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
	}
}


fun Double.unbinnedToNormalizedX(scaler: Scaler): Double = this/scaler.sourceDims.width
fun Double.unbinnedToNormalizedY(scaler: Scaler): Double = this/scaler.sourceDims.height

fun Double.normalizedToUnbinnedX(scaler: Scaler): Double = this*scaler.sourceDims.width
fun Double.normalizedToUnbinnedY(scaler: Scaler): Double = this*scaler.sourceDims.height


fun Double.flipNormalized(): Double = 1.0 - this

fun Double.unbinnedToBinned(scaler: Scaler, extraBinning: Int = 1): Double = this/scaler.sourceDims.binningFactor/extraBinning
fun Double.binnedToUnbinned(scaler: Scaler, extraBinning: Int = 1): Double = this*scaler.sourceDims.binningFactor*extraBinning

fun Double.binnedToNormalizedX(scaler: Scaler): Double = binnedToUnbinned(scaler).unbinnedToNormalizedX(scaler)
fun Double.binnedToNormalizedY(scaler: Scaler): Double = binnedToUnbinned(scaler).unbinnedToNormalizedY(scaler)

fun Double.normalizedToBinnedX(scaler: Scaler): Double = normalizedToUnbinnedX(scaler).unbinnedToBinned(scaler)
fun Double.normalizedToBinnedY(scaler: Scaler): Double = normalizedToUnbinnedY(scaler).unbinnedToBinned(scaler)

fun Int.sliceToBinnedZ(): Double = this.toDouble()*ImageDims.SLICE_FACTOR

fun Double.normalizedToPercent() = (this*100).perc

fun Double.unbinnedToA(scaler: Scaler): Double = this.unbinnedToA(scaler.scale)
fun Double.aToUnbinned(scaler: Scaler): Double = this.aToUnbinned(scaler.scale)
