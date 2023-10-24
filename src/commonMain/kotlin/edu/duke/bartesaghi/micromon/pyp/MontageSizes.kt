package edu.duke.bartesaghi.micromon.pyp

import kotlin.math.ceil
import kotlin.math.sqrt


data class MontageSizes(
	/** total number of tiles in the montage */
	val numTiles: Int,
	/** number of tiles in the x direction */
	val tilesX: Int,
	/** number of tiles in the y direction */
	val tilesY: Int,
	/** the width, in pixels, of a tile */
	val tileWidth: Int,
	/** the height, in pixels, of a tile */
	val tileHeight: Int
) {

	companion object {

		/**
		 * Calculate montage sizes from a "squared" montage image.
		 * That is, a montage image where the number of tiles in the
		 * x direction is the square root of the total number of tiles.
		 * */
		fun fromSquaredMontageImage(width: Int, height: Int, numTiles: Int): MontageSizes {

			val tilesX = ceil(sqrt(numTiles.toDouble())).toInt()
			val tilesY = ceil(numTiles.toDouble()/tilesX).toInt()

			val tileWidth = width/tilesX
			val tileHeight = height/tilesY

			return MontageSizes(numTiles, tilesX, tilesY, tileWidth, tileHeight)
		}
	}
}
