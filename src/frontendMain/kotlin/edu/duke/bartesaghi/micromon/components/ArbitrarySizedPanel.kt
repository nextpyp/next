package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.components.forms.enabled
import io.kvision.html.Button
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.utils.px


/**
 * Like a SizedPanel, but supports arbitrary sizes
 */
open class ArbitrarySizedPanel(
	title: String,
	val sizes: List<Int>,
	initialIndex: Int?,
	classes: Set<String> = emptySet()
) : Div(classes = setOf("sized-panel") + classes) {

	init {
		// just in case, make sure the sizes are sorted
		if (sizes.sorted() != sizes) {
			throw Error("${this::class.simpleName} sizes must be in increasing order")
		}
	}

	var onResize: (index: Int) -> Unit = {}

	var index: Int = initialIndex ?: 0
		set(value) {
			field = value
			updateSize()
			fireResize()
		}

	val rightDiv = Div(classes = setOf("right"))

	val plusButton = Button("", icon = "fas fa-plus")
		.onClick {
			if (index < sizes.size - 1) {
				index += 1
			}
		}

	val minusButton = Button("", icon = "fas fa-minus")
		.onClick {
			if (index > 0) {
				index -= 1
			}
		}

	private fun updateSize() {

		// update the panel size
		width = sizes[index].px

		// update the buttons
		plusButton.enabled =
			index < sizes.size - 1
		minusButton.enabled =
			index > 0
	}

	fun fireResize() {
		onResize(index)
	}

	init {

		// build the DOM
		div(classes = setOf("buttons")) {
			div(title, classes = setOf("title"))
			add(this@ArbitrarySizedPanel.rightDiv)
			this@ArbitrarySizedPanel.rightDiv.add(this@ArbitrarySizedPanel.minusButton)
			this@ArbitrarySizedPanel.rightDiv.add(this@ArbitrarySizedPanel.plusButton)
		}

		updateSize()
	}
}
