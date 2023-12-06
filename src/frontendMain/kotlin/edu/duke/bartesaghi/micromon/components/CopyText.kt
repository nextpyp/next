package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.components.forms.copyToClipboard
import edu.duke.bartesaghi.micromon.components.forms.selectAll
import io.kvision.core.onEvent
import io.kvision.form.text.text
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.toast.Toast


/**
 * A text box that easily allows copying text
 */
class CopyText(
	initialValue: String? = null,
	toast: String = "Value copied to the clipboard"
) : Div(classes = setOf("copy-text")) {

	val valueText = text(value = initialValue)
		.apply {
			readonly = true
		}

	val copyButton = button(
		text = "Copy",
		icon = "fas fa-copy"
	)

	init {

		// wire up events
		valueText.onEvent {
			focus = {
				valueText.input.selectAll()
			}
		}

		copyButton.onClick {
			if (valueText.input.copyToClipboard()) {
				Toast.info(toast)
			}
		}
	}
}
