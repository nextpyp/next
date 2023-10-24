package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.niceAndRound
import edu.duke.bartesaghi.micromon.pyp.*
import io.kvision.core.onEvent
import io.kvision.core.style
import io.kvision.html.Div
import io.kvision.html.div


class ScaleBar(val scaler: Scaler?) : Div(classes = setOf("scale-bar", "right")) {

	val bar = div(classes = setOf("bar"))
	val label = div(classes = setOf("label"))

	init {

		if (scaler == null) {

			// no scale info, show a placeholder
			bar.visible = false
			label.content = "(no scale info)"

		} else {

			// convert 1/5 of the image size to a nice round number in Angstroms
			val lenA = (0.2)
				.normalizedToUnbinnedX(scaler)
				.unbinnedToA(scaler)
				.niceAndRound()

			// set the width of the outer element since it's parented to
			// theimage, whose width we're normalizing against
			style {
				width = lenA.toDouble()
					.aToUnbinned(scaler)
					.unbinnedToNormalizedX(scaler)
					.normalizedToPercent()
			}

			// add a text label
			label.content = "$lenA A"
		}

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
}
