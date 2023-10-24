package edu.duke.bartesaghi.micromon.components.forms

import io.kvision.html.Span


// NOTE: not a FontAwesome icon, doesn't need to inherit from the Icon framework
class ArgIcon(state: State = State.Default) : Span(classes = setOf("arg-icon")) {

	enum class State(val text: String, val css: String, val description: String) {
		Default("D", "arg-icon-default", "This argument remains at the (D)efault value."),
		Specified("S", "arg-icon-specified", "This argument has been (S)pecified with a non-default value."),
		Required("R", "arg-icon-required", "This argument is (R)equired.")
	}

	var state: State = state
		set(value) {
 			field = value
			redraw()
		}

	private fun redraw() {
		for (state in State.values()) {
			removeCssClass(state.css)
		}
		addCssClass(state.css)
		content = state.text
		title = state.description
	}
}
