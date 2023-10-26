package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.services.ImageSize
import io.kvision.html.Button
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.utils.px
import kotlinx.browser.window


/**
 * A panel with buttons to changes its width to one of the three ImageSizes
 */
open class SizedPanel(
	title: String,
	initialSize: ImageSize?,
	val defaultToLarge: Boolean = true,
	val includeLarge: Boolean = true,
	val includeSmall: Boolean = true,
	classes: Set<String> = emptySet()
) : Div(classes = setOf("sized-panel") + classes) {

	var panelTitle: String = title
		set(value) {
			field = value
			titleElem.content = value
		}

	var onResize: (ImageSize) -> Unit = {}

	private val titleElem = Div(title, classes = setOf("title"))

	val rightDiv = Div(classes = setOf("right"))

	val plusButton = Button("", icon = "fas fa-plus")
		.onClick {
			when (this@SizedPanel.size) {
				ImageSize.Small -> this@SizedPanel.size = ImageSize.Medium
				ImageSize.Medium -> if (includeLarge) {
					this@SizedPanel.size = ImageSize.Large
				} else {
					Unit
				}
				ImageSize.Large -> Unit
			}
		}

	val minusButton = Button("", icon = "fas fa-minus")
		.onClick {
			when (this@SizedPanel.size) {
				ImageSize.Small -> Unit
				ImageSize.Medium -> if (includeSmall) {
					this@SizedPanel.size = ImageSize.Small
				} else {
					Unit
				}
				ImageSize.Large -> this@SizedPanel.size = ImageSize.Medium
			}
		}

	// pick a default size that fits in the browser window
	val defaultSize = when (window.innerWidth) {
		in 0 until 600 -> ImageSize.Small
		in 600 until 1200 -> ImageSize.Medium
		else -> if (includeLarge && defaultToLarge) {
			ImageSize.Large
		} else {
			ImageSize.Medium
		}
	}

	var size: ImageSize = initialSize ?: defaultSize
		set(newSize) {
			field = newSize

			updateSize()

			// bubble events back to the caller
			fireResize()
		}

	private fun updateSize() {

		// update the panel size
		width = size.approxWidth.px

		// update the buttons
		plusButton.disabled =
			if (includeLarge) {
				size == ImageSize.Large
			} else {
				size == ImageSize.Medium
			}
		minusButton.disabled =
			if (includeSmall) {
				size == ImageSize.Small
			} else {
				size == ImageSize.Medium
			}
	}

	fun fireResize() {
		onResize(size)
	}

	init {

		// build the DOM
		div(classes = setOf("buttons")) {
			add(this@SizedPanel.titleElem)
			add(this@SizedPanel.rightDiv)
			this@SizedPanel.rightDiv.add(this@SizedPanel.minusButton)
			this@SizedPanel.rightDiv.add(this@SizedPanel.plusButton)
		}

		updateSize()
	}
}
