package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.services.ImageSize
import io.kvision.html.image
import kotlin.reflect.KMutableProperty0


class ImagePanel(
	title: String,
	private val sizeStorage: KMutableProperty0<ImageSize?>? = null,
	initialSize: ImageSize? = null,
	pather: (ImageSize) -> String
): SizedPanel(title, sizeStorage?.get() ?: initialSize) {

	val img = image(pather(size), classes = setOf("full-width-image"))

	init {
		onResize = { newSize: ImageSize ->
			sizeStorage?.set(newSize)
			img.src = pather(size)
		}
	}
}
