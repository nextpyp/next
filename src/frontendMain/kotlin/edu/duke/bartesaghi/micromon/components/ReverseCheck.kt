package edu.duke.bartesaghi.micromon.components

import io.kvision.core.onEvent
import io.kvision.html.Div
import org.w3c.dom.events.Event


/**
 * A checkbox that de-selects things, rather than selects them
 */
class ReverseCheck(
	state: State = State.Ignored
) : Div(classes = setOf("reverse-check", "far")) {

	enum class State(val icon: String) {
		Ignored("fa-circle"),
		Rejected("fa-times-circle")
	}

	var state: State = state
		set(value) {
			field = value
			update()

			// send the change event
			getSnOn()?.run {
				change?.invoke(Event("change"))
			}
		}

	init {
		update()

		// wire up events
		onEvent {
			click = {
				onClick()
			}
		}
	}

	private fun update() {

		// remove the old styles
		State.values().forEach { removeCssClass(it.icon) }

		// add the new styles
		addCssClass(state.icon)
		title = state.name
	}

	private fun onClick() {

		// cycle through the options
		val i = (state.ordinal + 1) % State.values().size
		state = State.values()[i]
	}
}
