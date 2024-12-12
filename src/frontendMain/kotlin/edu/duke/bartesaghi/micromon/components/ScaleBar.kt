package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.niceAndRound
import edu.duke.bartesaghi.micromon.normalizedToPercent
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.toFixed
import io.kvision.core.onEvent
import io.kvision.core.style
import io.kvision.html.Div
import io.kvision.html.div


class ScaleBar(val dims: ImageDims) : Div(classes = setOf("scale-bar", "right")) {

	val bar = div(classes = setOf("bar"))
	val label = div(classes = setOf("label"))

	// convert 1/5 of the image size to a nice round number in Angstroms
	val initialLen: ValueA = 0.2
		.normalizedToUnbinnedX(dims)
		.toA(dims)
		.let { ValueA(it.v.niceAndRound().toDouble()) }

	// the currently showing length, in Angstroms
	var len: ValueA = initialLen
		set(value) {
			field = value
			update()
		}

	init {

		update()

		// if we mouse over the bar, move it to the other corner
		onEvent {
			mouseenter = {
				if (hasCssClass("right")) {
					removeCssClass("right")
					addCssClass("left")
				} else if (hasCssClass("left")) {
					removeCssClass("left")
					addCssClass("right")
				}
			}
		}
	}

	fun update() {

		// set the width of the outer element since it's parented to
		// theimage, whose width we're normalizing against
		style {
			width = len
				.toUnbinned(dims)
				.toNormalizedX(dims)
				.normalizedToPercent()
		}

		label.content = "${len.v.toFixed(0)} A"
	}
}
