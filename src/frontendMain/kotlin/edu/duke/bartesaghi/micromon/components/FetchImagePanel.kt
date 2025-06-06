package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.services.ImageSize
import kotlin.reflect.KMutableProperty0


class FetchImagePanel(
	title: String,
	private val sizeStorage: KMutableProperty0<ImageSize?>? = null,
	initialSize: ImageSize? = null,
	var pather: (ImageSize) -> String
): SizedPanel(title, sizeStorage?.get() ?: initialSize) {

	val img = fetchImage(classes = setOf("full-width-image"))

	init {
		fetch()

		onResize = { newSize: ImageSize ->
			sizeStorage?.set(newSize)
			fetch()
		}
	}

	fun fetch() {
		img.fetch(pather(size))
	}
}


class ArbitraryFetchImagePanel(
	title: String,
	sizes: List<Int>,
	private val sizeStorage: KMutableProperty0<Int?>? = null,
	initialIndex: Int? = null,
	var pather: (Int) -> String
): ArbitrarySizedPanel(title, sizes, sizeStorage?.get() ?: initialIndex) {

	val img = fetchImage(classes = setOf("full-width-image"))

	init {
		fetch()

		onResize = { newIndex: Int ->
			sizeStorage?.set(newIndex)
			fetch()
		}
	}

	fun fetch() {
		img.fetch(pather(index))
	}
}
