package edu.duke.bartesaghi.micromon.components

import io.kvision.core.onEvent
import io.kvision.html.Div


class TriStateCheck(
	state: State = State.Ignored
) : Div(classes = setOf("tristate-check", "far")) {

	enum class State(val icon: String) {
		Ignored("fa-circle"),
		Accepted("fa-check-circle"),
		Rejected("fa-times-circle")
	}

	var state: State = state
		set(value) {
			field = value
			update()
		}

	init {
		update()

		// wire up events
		onEvent {
			click = {
				onClick()

				// propagate events
				getSnOn()?.run {
					change?.invoke(it)
				}
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
