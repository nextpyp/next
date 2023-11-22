package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.niceAndRound
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.toFixed
import io.kvision.core.onEvent
import io.kvision.core.style
import io.kvision.html.Div
import io.kvision.html.div


class ScaleBar(val scaler: Scaler?) : Div(classes = setOf("scale-bar", "right")) {

	val bar = div(classes = setOf("bar"))
	val label = div(classes = setOf("label"))

	// convert 1/5 of the image size to a nice round number in Angstroms
	val initialLenA: Double? =
		scaler?.let {
			(0.2)
				.normalizedToUnbinnedX(it)
				.unbinnedToA(it)
				.niceAndRound()
				.toDouble()
		}

	// the currently showing length, in Angstroms
	var lenA: Double? = initialLenA
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

		val scaler = scaler
			?: run {
				// no scale info, show a placeholder
				bar.visible = false
				label.content = "(no scale info)"
				return
			}

		val lenA = lenA
			?: 0.0

		// set the width of the outer element since it's parented to
		// theimage, whose width we're normalizing against
		style {
			width = lenA
				.aToUnbinned(scaler)
				.unbinnedToNormalizedX(scaler)
				.normalizedToPercent()
		}

		label.content = "${lenA.toFixed(0)} A"
	}
}
