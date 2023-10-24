package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.components.forms.enabled
import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.Div


/**
 * A small UI element that highlights a bit of content
 */
open class Chip(
	classes: Set<String> = emptySet()
) : Div(classes = classes + setOf("chip"))

fun Container.chip(classes: Set<String> = emptySet(), block: Chip.() -> Unit): Chip {
	val chip = Chip(classes)
	add(chip)
	chip.block()
	return chip
}


/**
 * A chip with a small button on the left
 */
class ButtonChip(icon: String, classes: Set<String> = emptySet()) : Chip(classes) {

	val button = Button("", icon, classes = setOf("chip-button"))

	private var iconBackup: String? = null

	var waiting: Boolean = false
		set(value) {
			if (field == value) {
				return
			} else if (value) {

				// start waiting
				iconBackup = button.icon
				button.enabled = false
				button.icon = "fas fa-spinner fa-pulse"

			} else {

				// stop waiting
				button.icon = iconBackup
				iconBackup = null
				button.enabled = true
			}
			field = value
		}

	init {
		add(button)
	}
}


fun Container.buttonChip(icon: String, block: ButtonChip.() -> Unit): ButtonChip {
	val chip = ButtonChip(icon)
	add(chip)
	chip.block()
	return chip
}
