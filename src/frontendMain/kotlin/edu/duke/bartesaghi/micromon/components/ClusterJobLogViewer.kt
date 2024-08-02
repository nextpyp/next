package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.check.CheckBox
import io.kvision.form.select.SelectInput
import io.kvision.form.spinner.SpinnerInput
import io.kvision.html.*
import io.kvision.modal.Modal


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
	private val tabs = LazyTabPanel(classes = setOf("tabs"))
	private val streamTab = Div(classes = setOf("stream"))
	private val logsTab = Div(classes = setOf("logs"))

	private val logStreamer = LogStreamer(RealTimeC2S.ListenToJobStreamLog(clusterJobId), true)
		.apply {
			onEnd = {
				// clear the existing data so the next tab load has to refresh it
				clusterJobLog = null
			}
			onPin = ::pinStream
			streamTab.add(this)
		}

	private var clusterJobLog: ClusterJobLog? = null
	private var pinned = false

	init {

		// layout the UI
		win.add(debugElem)
		tabs.addTab("Stream") { tab ->
			tab.elem.add(streamTab)
		}
		tabs.addTab("Logs") { tab ->
			tab.elem.add(logsTab)

			AppScope.launch {
				// load new data, if needed
				if (clusterJobLog == null) {
					clusterJobLog = load()
				}
				clusterJobLog?.let { updateLogs(it) }
			}
		}
		win.add(tabs)
		tabs.activateDefaultTab()

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
			clusterJobLog = load()
			clusterJobLog?.let { updateDebug(it) }
		}
	}

	private suspend fun load(): ClusterJobLog? {

		// load initial info from the server
		val loading = win.loading("Loading logs ...")
		try {
			return Services.projects.clusterJobLog(clusterJobId)
		} catch (t: Throwable) {
			win.errorMessage(t)
			return null
		} finally {
			win.remove(loading)
		}
	}

	private fun updateDebug(clusterJobLog: ClusterJobLog) {

		debugElem.removeAll()

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
					h2("Arguments")
					div(classes = setOf("commands", "section")) {
						if (clusterJobLog.launchResult.arguments.isNotEmpty()) {
							for (arg in clusterJobLog.launchResult.arguments) {
								div(arg)
							}
						} else {
							span("(none)", classes = setOf("empty"))
						}
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
	}

	private fun updateLogs(clusterJobLog: ClusterJobLog) {

		// make the log section
		val logElem = Div(classes = setOf("log"))

		fun setLog(arrayId: Int?, resultType: ClusterJobResultType?, exitCode: Int?, log: String?) {
			logElem.removeAll()
			logElem.div(classes = setOf("header")) {
				if (arrayId != null) {
					span("Array Job: $arrayId")
				}
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
					setLog(arrayId, arrayLog.resultType, arrayLog.exitCode, arrayLog.log)
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
			setLog(null, clusterJobLog.resultType, clusterJobLog.exitCode, clusterJobLog.log)

		} else {
			navElem = Div(classes = setOf("array-controls")).apply {

				span("Array Job Log:")

				// add some controls to show other logs in the array
				val arrayIdSpinner = SpinnerInput(
					value = 1,
					min = 1,
					max = clusterJobLog.arraySize
				)
				val failedArrayIdDropdown = SelectInput(
					options = clusterJobLog.failedArrayIds
						.map { failedId -> "$failedId" to "$failedId" }
				)
				val idContainer = Span()
				val showOnlyFailuresCheck = CheckBox(
					value = false,
					label = run {
						val failuresMsg = when (val num = clusterJobLog.failedArrayIds.size) {
							0 -> "no failures"
							1 -> "$num failure"
							else -> "$num failures"
						}
						"Show only failed jobs ($failuresMsg)"
					}
				) {
					inline = true
					enabled = clusterJobLog.failedArrayIds.isNotEmpty()
				}
				val loadButton = Button("Load")

				// layout the UI
				add(idContainer)
				span("of ${clusterJobLog.arraySize}")
				add(loadButton)
				add(showOnlyFailuresCheck)

				// wire up events
				fun updateVisibility() {
					idContainer.removeAll()
					if (showOnlyFailuresCheck.value) {
						idContainer.span {
							// NOTE: surround in a span to work around an apparent bug in snabdom
							//       where the dropdown can't be removed from the real DOM
							add(failedArrayIdDropdown)
						}
					} else {
						idContainer.add(arrayIdSpinner)
					}
				}
				showOnlyFailuresCheck.onEvent {
					change = {
						updateVisibility()
					}
				}
				loadButton.onClick {

					val arrayId: Int? =
						if (showOnlyFailuresCheck.value) {
							failedArrayIdDropdown.value
								?.toIntOrNull()
						} else {
							arrayIdSpinner.value
								?.toInt()
						}
					if (arrayId != null) {
						loadArrayLog(arrayId)
					}
				}

				// init UI state, load the first log by default
				updateVisibility()
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
		logStreamer.close()
	}

	private fun pinStream() {

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
