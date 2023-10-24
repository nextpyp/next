package edu.duke.bartesaghi.micromon.views.admin

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.loading
import edu.duke.bartesaghi.micromon.services.Services
import edu.duke.bartesaghi.micromon.services.unwrap
import io.kvision.core.Container
import io.kvision.html.*
import io.kvision.modal.Modal
import io.kvision.remote.ServiceException


class AdminPypTab(val elem: Container) {

	companion object {
		const val Title = "PYP/WebRPC Ping"
	}

	private val pingButton = Button(Title).onClick {

		// disable the button while we wait for the ping
		enabled = false
		icon = "fas fa-spinner fa-pulse"

		AppScope.launch {

			// make a popup to show the ping response
			val win = Modal(
				caption = title,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "max-height-dialog", "full-width-dialog")
			)
			val loading = win.loading("Sending ping ...")
			win.show()

			try {

				// send the ping request, wait for the response
				val response = Services.admin.pypRpcPing()
					.unwrap()

				// show the response in the popup
				if (response != null) {
					win.div("Result Type: ${response.resultType}")
					win.div("Exit code: ${response.exitCode ?: "(none)"}")
					win.tag(TAG.PRE).content = response.output ?: "(no output)"
				} else {
					win.p("No response was received")
				}

			} catch (ex: ServiceException) {
				win.p("The ping request could not be sent: ${ex.message?.takeIf { it.isNotBlank() } ?: "(no reason)"}}")
			} finally {

				win.remove(loading)

				// restore the button state
				enabled = true
				icon = null
			}
		}
	}

	init {
		// layout elements
		elem.addCssClass("tab")
		elem.div {
			add(pingButton)
			add(p("Launches a PYP command that just sends a ping request to the website over the RPC channel."))
			add(p("Useful for determining if PYP can access the website API or not."))
		}
	}
}