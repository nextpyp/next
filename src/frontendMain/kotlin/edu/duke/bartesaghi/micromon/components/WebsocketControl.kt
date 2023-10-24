package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.WebsocketConnector
import io.kvision.core.Trigger
import io.kvision.html.*
import js.getHTMLElement
import kotlinx.browser.document
import kotlinx.dom.clear
import kotlinx.html.*
import kotlinx.html.dom.create
import kotlinx.html.js.onClickFunction
import org.w3c.dom.HTMLElement
import org.w3c.dom.get


class WebsocketControl(val connector: WebsocketConnector) : Div(classes = setOf("websocket-control")) {

	private var icon: Icon? = null

	init {
		connector.onStateChange = { _, ex -> update(ex) }
	}

	private fun update(t: Throwable?) {

		// if for some reason this control isn't in the real DOM yet, the popover stuff won't work
		// try to warn about failures early
		if (getHTMLElement() == null) {
			console.warn("WebsocketControl not in real DOM yet, popover functions are likely to fail, this is probably some kind of race condition?")
		}

		// for the DSLs that can't reference this scope
		val connector = connector

		// close any existing popovers
		icon?.closePopover()

		removeAll()

		fun reconnect() {
			AppScope.launch {
				connector.connect()
			}
		}

		fun disconnect() {
			connector.disconnect()
		}

		when (connector.state) {

			WebsocketConnector.State.Disconnected -> {

				// show the state icon
				if (t != null) {

					// log the error directly to the console, just in case any devs are looking
					t.reportError()

					icon = icon("fas fa-bug")
						.apply {
							title = "Disconnected from the server due to an error"
							popover(triggers = setOf(Trigger.CLICK), offset = 0 to 8) {
								document.create.div {
									p {
										+"You are not connected to the server and will not receive any streaming updates until you reconnect."
									}
									p {
										button {
											classes = setOf("btn", "btn-primary")
											+"Reconnect"
											onClickFunction = {
												closePopover()
												reconnect()
											}
										}
									}
									connector.finalIdleReport?.let { idle ->
										p {
											+"This connection was open for "
											+idle.openSeconds.formatSecondsDuration()
											+" and was idle for "
											+idle.idleSeconds.formatSecondsDuration()
											+" of a possible "
											+idle.timeoutSeconds.formatSecondsDuration()
											+"."
										}
									}
									p {
										+"Additionally, the disconnection occurred because of an error:"
									}
									p {
										t.message
											?.let {
												pre {
													classes = setOf("error-message")
													+it
												}
											} ?: run {
												div {
													classes = setOf("error-message")
													+"(no error message)"
												}
											}
									}
									p {
										+"Type: "
										pre {
											classes = setOf("error-type")
											+(t::class.simpleName ?: t::class.toString())
										}
									}
								}
							}
						}

				} else {

					icon = icon("fas fa-plug")
						.apply {
							title = "Disconnected from the server"
							popover(triggers = setOf(Trigger.CLICK), offset = 0 to 8) {
								document.create.div {
									p {
										+"You are not connected to the server and will not receive any streaming updates until you reconnect."
									}
									connector.finalIdleReport?.let { idle ->
										p {
											+"This connection was open for "
											+idle.openSeconds.formatSecondsDuration()
											+" and was idle for "
											+idle.idleSeconds.formatSecondsDuration()
											+" of a possible "
											+idle.timeoutSeconds.formatSecondsDuration()
											+"."
										}
									}
									p {
										button {
											classes = setOf("btn", "btn-primary")
											+"Reconnect"
											onClickFunction = {
												closePopover()
												reconnect()
											}
										}
									}
								}
							}
						}
				}
			}

			WebsocketConnector.State.Connecting -> {

				icon = icon("fas fa-spinner fa-pulse").apply {
					title = "Connecting to the server"
					popover(triggers = setOf(Trigger.CLICK), offset = 0 to 8) {
						document.create.div {
							p {
								+"You are currently trying to connect to the server. Please wait."
							}
							p {
								button {
									classes = setOf("btn", "btn-primary")
									+"Stop"
									onClickFunction = {
										closePopover()
										disconnect()
									}
								}
							}
						}
					}
				}
			}

			WebsocketConnector.State.Connected -> {

				icon = icon("fas fa-wifi").apply {
					title = "Connected to the server"
					popover(triggers = setOf(Trigger.CLICK), offset = 0 to 8) {
						document.create.div {
							p {
								+"You are currently connected to the server and receiving streaming updates."
							}
							div {
								classes = setOf("idleReport")
							}
							p {
								button {
									classes = setOf("btn", "btn-primary")
									+"Disconnect"
									onClickFunction = {
										closePopover()
										disconnect()
									}
								}
							}
						}
					}?.apply {

						// update the idle report every time the popup is shown
						onInserted {

							// find the idle report elem
							val idleReportElem = popoverElement()
								?.getElementsByClassName("idleReport")
								?.get(0) as HTMLElement?
								?: return@onInserted

							idleReportElem.clear()
							idleReportElem.append(document.create.p {
								val idle = connector.idleReport()
								if (idle != null) {
									+"This connection has been open for "
									+idle.openSeconds.formatSecondsDuration()
									+" and has been idle for "
									+idle.idleSeconds.formatSecondsDuration()
									+" of a possible "
									+idle.timeoutSeconds.formatSecondsDuration()
									+"."
								} else {
									+"Idle status in unknown."
								}
							})
						}
					}
				}
			}

			WebsocketConnector.State.Disconnecting -> {

				icon = icon("fas fa-spinner fa-pulse").apply {
					title = "Disconnecting from the server"
				}
			}
		}
	}
}
