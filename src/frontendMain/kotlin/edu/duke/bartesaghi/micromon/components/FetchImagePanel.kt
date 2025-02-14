package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.services.ImageSize
import kotlin.reflect.KMutableProperty0


class FetchImagePanel(
	title: String,
	private val sizeStorage: KMutableProperty0<ImageSize?>? = null,
	initialSize: ImageSize? = null,
	pather: (ImageSize) -> String
): SizedPanel(title, sizeStorage?.get() ?: initialSize) {

	val img = fetchImage(classes = setOf("full-width-image"))

	init {
		img.fetch(pather(size))

		onResize = { newSize: ImageSize ->
			sizeStorage?.set(newSize)
			img.fetch(pather(size))
		}
	}
}
