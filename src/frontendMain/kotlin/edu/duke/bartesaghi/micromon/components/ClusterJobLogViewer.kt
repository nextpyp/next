package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.spinner.SpinnerInput
import io.kvision.html.*
import io.kvision.modal.Modal
import io.kvision.panel.TabPanel


class ClusterJobLogViewer(
	val clusterJobId: String,
	val name: String,
	val panelBottom: Container
) {

	private val title = "Logs for $name job"

	// show the logs in a popup window
	private val win = Modal(
		caption = title,
		escape = true,
		closeButton = true,
		classes = setOf("dashboard-popup", "cluster-job-logs", "max-height-dialog")
	)

	private val debugElem = Div(classes = setOf("debug-info"))
	private val tabs = TabPanel(classes = setOf("tabs"))
	private val streamTab = Div(classes = setOf("stream"))
	private val logsTab = Div(classes = setOf("logs"))

	private var logStreamer: LogStreamer? = null
	private var pinned = false

	init {

		// layout the UI
		win.add(debugElem)
		tabs.addTab("Stream", streamTab)
		tabs.addTab("Logs", logsTab)
		win.add(tabs)

		// wire up events
		win.onEvent {

			// make sure to cleanly close the websocket connection when we close the popup
			hideBsModal = {
				if (!pinned) {
					close()
				}
			}
		}

		win.show()

		AppScope.launch {

			// load initial info from the server
			val loading = win.loading("Loading logs ...")
			val clusterJobLog = try {
				Services.projects.clusterJobLog(clusterJobId)
			} catch (t: Throwable) {
				win.errorMessage(t)
				return@launch
			} finally {
				win.remove(loading)
			}

			// show the debug commands
			debugElem.disclosure(
				label = {
					span("Launch Info")
				},
				disclosed = d@{
					if (clusterJobLog.submitFailure != null) {

						h2("Success:")
						div(classes = setOf("section")) {
							content = "No"
						}
						h2("Reason:")
						div(classes = setOf("section")) {
							content = clusterJobLog.submitFailure
						}

					} else if (clusterJobLog.launchResult != null) {

						h2("Success:")
						div(classes = setOf("section")) {
							content = if (clusterJobLog.launchResult.success) {
								"Yes"
							} else {
								"No"
							}
						}
						h2("Command")
						div(classes = setOf("commands", "section")) {
							content = clusterJobLog.launchResult.command ?: "(no launch command)"
						}
						h2("Console Output")
						div(classes = setOf("commands", "section")) {
							content = clusterJobLog.launchResult.out
						}

					} else {

						span("(none)")
					}
				}
			).apply {
				// automatically show the launch info if there was a launch problem
				open = clusterJobLog.submitFailure != null
					|| clusterJobLog.launchResult?.success == false
			}
			debugElem.disclosure(
				label = {
					span("Commands")
				},
				disclosed = {
					div(classes = setOf("commands")) {
						content = clusterJobLog.representativeCommand
					}
				}
			).apply {
				open = false
			}

			logStreamer = LogStreamer(RealTimeC2S.ListenToJobStreamLog(clusterJobId), true)
				.also {
					it.onPin = ::pinStream
					streamTab.add(it)
				}

			showLogs(clusterJobLog)
		}
	}

	private fun showLogs(clusterJobLog: ClusterJobLog) {

		// make the log section
		val logElem = Div(classes = setOf("log"))

		fun setLog(resultType: ClusterJobResultType?, exitCode: Int?, log: String?) {
			logElem.removeAll()
			logElem.div(classes = setOf("header")) {
				span(classes = setOf("result")) {
					span("Result: ")
					when (resultType) {
						ClusterJobResultType.Success -> "fas fa-check-circle"
						ClusterJobResultType.Failure -> "fas fa-exclamation-triangle"
						ClusterJobResultType.Canceled -> "fas fa-ban"
						null -> null
					}?.let {
						iconStyled(it, classes = setOf("icon"))
					}
					span(resultType?.name ?: "(none)", classes = setOf("result"))
				}
				span("Exit code: ${exitCode ?: "(none)"}")
			}
			logElem.add(LogView.fromText(log))
		}

		fun loadArrayLog(arrayId: Int) {
			AppScope.launch {
				logElem.removeAll()
				val loadingLog = logElem.loading("Loading log ...")
				try {
					val arrayLog = Services.projects.clusterJobArrayLog(clusterJobId, arrayId)
					setLog(arrayLog.resultType, arrayLog.exitCode, arrayLog.log)
				} catch (t: Throwable) {
					logElem.errorMessage(t)
				} finally {
					logElem.remove(loadingLog)
				}
			}
		}

		var navElem: Div? = null
		if (clusterJobLog.arraySize == null) {

			// show the one and only log
			setLog(clusterJobLog.resultType, clusterJobLog.exitCode, clusterJobLog.log)

		} else {
			navElem = Div().apply {
				span("Array job has logs for jobs from 1 to ${clusterJobLog.arraySize}")

				// add some controls to show other logs in the array
				val arrayIdSpinner = SpinnerInput(
					value = 1,
					min = 1,
					max = clusterJobLog.arraySize
				)
				div(classes = setOf("array-id")) {
					add(arrayIdSpinner)
				}
				button("Load").apply {
					onClick {
						arrayIdSpinner.value
							?.toInt()
							?.let { loadArrayLog(it) }
					}
				}

				// load the first log by default
				loadArrayLog(1)
			}
		}

		// layout the UI
		logsTab.removeAll()
		if (navElem != null) {
			logsTab.add(navElem)
		}
		logsTab.add(logElem)
	}

	private fun close() {
		logStreamer?.close()
	}

	private fun pinStream() {

		val logStreamer = logStreamer ?: run {
			console.warn("No log streamer, can't move it!")
			return
		}

		pinned = true
		win.hide()
		logStreamer.pinButton.visible = false

		// make a new spot in the bottom panel of the project view
		val elem = Div(classes = setOf("cluster-job-logs-pinned"))
		panelBottom.add(elem)

		val unpinButton = Button(
			"", "fas fa-thumbtack",
			classes = setOf("button")
		).apply {
			title = "Unpin this log back to a popup window"
			onClick {
				unpinStream()
			}
		}

		val closeButton = Button(
			"", "fas fa-times",
			classes = setOf("button")
		).apply {
			title = "Close this log"
			onClick {
				close()
				panelBottom.remove(elem)
			}
		}

		elem.div(classes = setOf("header")) {
			span(this@ClusterJobLogViewer.title, classes = setOf("title"))
			add(unpinButton)
			add(closeButton)
		}

		// move the log streamer from the popup to the bottom panel
		streamTab.remove(logStreamer)
		elem.add(logStreamer)

		if (logStreamer.autoScroll.value) {
			logStreamer.scrollToBottom()
		}
	}

	private fun unpinStream() {

		val logStreamer = logStreamer ?: run {
			console.warn("No log streamer, can't move it!")
			return
		}

		pinned = false

		// move the log streamer from the bottom panel back to the popup
		logStreamer.parent?.let { panelBottom.remove(it) }
		streamTab.add(logStreamer)

		if (logStreamer.autoScroll.value) {
			logStreamer.scrollToBottom()
		}

		logStreamer.pinButton.visible = true
		win.show()
	}
}
