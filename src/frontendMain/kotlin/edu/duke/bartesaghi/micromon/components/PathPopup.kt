package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.components.forms.copyToClipboard
import edu.duke.bartesaghi.micromon.components.forms.selectAll
import io.kvision.core.onEvent
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.modal.Modal
import io.kvision.toast.Toast


class PathPopup(
	val title: String,
	val path: String
) {

	companion object {

		fun show(title: String, path: String) =
			PathPopup(title, path).show()

		fun button(title: String, path: String) =
			Button(
				text = "",
				icon = "fas fa-location-arrow"
			).apply {
				this.title = "Show the filesystem location"
				onClick {
					show(title, path)
				}
			}
	}

	fun show() {

		val win = Modal(
			caption = title,
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup")
		)

		val pathText = win.text(value = path).apply {
			readonly = true
		}
		pathText.onEvent {
			focus = {
				pathText.input.selectAll()
			}
		}

		// copy button
		win.addButton(Button(
			text = "Copy",
			icon = "fas fa-copy"
		).apply {
			onClick {
				if (pathText.input.copyToClipboard()) {
					Toast.info("Path copied to clipboard")
				}
			}
		})

		// ok button
		win.addButton(Button("Ok").apply {
			onClick {
				win.hide()
			}
		})

		win.show()
	}
}
