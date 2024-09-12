package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.errorMessage
import edu.duke.bartesaghi.micromon.reportError
import io.kvision.html.Div


class ErrorView(val error: Throwable) : View {

	constructor (msg: String) : this(Exception(msg))

	// dump the error to the js console
	init {
		error.reportError()
	}

	override val routed = null
	override val elem = Div(classes = setOf("dock-page"))

	override fun init(viewport: Viewport) {
		elem.errorMessage(error)
	}
}
