package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.toArgValues
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.check.CheckBox
import io.kvision.form.select.SelectInput
import io.kvision.form.spinner.SpinnerInput
import io.kvision.html.*
import io.kvision.modal.Modal


class ClusterJobLogViewer(
	val job: JobData,
	val clusterJobId: String,
	val name: String,
	val panelBottom: Container
) {

	private val title = "Logs for: $name"

	// show the logs in a popup window
	private val win = Modal(
		caption = title,
		escape = true,
		closeButton = true,
		classes = setOf("dashboard-popup", "cluster-job-logs", "max-height-dialog")
	)

	private val tabs = LazyTabPanel(classes = setOf("tabs"))
	private val launchElem = Div(classes = setOf("launch"))
	private val streamElem = Div(classes = setOf("stream"))
	private val logsElem = Div(classes = setOf("logs"))

	private val logStreamer = LogStreamer(RealTimeC2S.ListenToJobStreamLog(clusterJobId), true)
		.apply {
			onEnd = {
				// clear the existing data so the next tab load has to refresh it
				clusterJobLog = null
			}
			onPin = ::pinStream
			streamElem.add(this)
		}

	private var clusterJobLog: ClusterJobLog? = null
	private var pinned = false

	init {

		// layout the UI
		val launchTab = tabs.addTab("Launch") { tab ->
			tab.elem.add(launchElem)
		}
		val streamTab = tabs.addTab("Stream") { tab ->
			tab.elem.add(streamElem)
		}
		val logTab = tabs.addTab("Logs") { tab ->
			tab.elem.add(logsElem)

			AppScope.launch {
				// load new data, if needed
				if (clusterJobLog == null) {
					clusterJobLog = load()
				}
				clusterJobLog?.let { updateLogs(it) }
			}
		}
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
			clusterJobLog = load()
			clusterJobLog?.let { log ->

				// show a default tab depending on the cluster job state
				val showLaunchTab = updateLaunch(log)
				tabs.initWithDefaultTab(if (showLaunchTab) {
					launchTab
				} else if (log.resultType != null) {
					logTab
				} else {
					streamTab
				})
			}
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

	private fun updateLaunch(clusterJobLog: ClusterJobLog): Boolean {

		// show the launch tab by default, since that's where the launch errors will be
		var showLaunchTab = true

		launchElem.removeAll()

		// show the launch info
		launchElem.h1("Launch Info")
		if (clusterJobLog.submitFailure != null) {

			launchElem.h2("Success:")
			launchElem.div(classes = setOf("section")) {
				content = "No"
			}
			launchElem.h2("Reason:")
			launchElem.div(classes = setOf("section")) {
				content = clusterJobLog.submitFailure
			}

		} else if (clusterJobLog.launchResult != null) {

			launchElem.h2("Success:")
			launchElem.div(classes = setOf("section")) {
				content =
					if (clusterJobLog.launchResult.success) {
						// launch worked! show something else instead
						showLaunchTab = false
						"Yes"
					} else {
						"No"
					}
			}
			launchElem.h2("Command")
			launchElem.div(classes = setOf("commands", "section")) {
				content = clusterJobLog.launchResult.command ?: "(no launch command)"
			}
			launchElem.h2("Console Output")
			launchElem.div(classes = setOf("commands", "section")) {
				val log = clusterJobLog.launchResult.out
					.takeIf { it.isNotBlank() }
				if (log != null) {
					add(LogView.fromText(log))
				} else {
					span("(none)", classes = setOf("empty"))
				}
			}

		} else {

			launchElem.h2("Waiting:")
			val elem = launchElem.div()

			// job not launched yet? show waiting reason
			val loading = elem.loading("Checking ...")
			AppScope.launch {

				val reason = try {
					Services.projects.waitingReason(clusterJobId)
				} catch (t: Throwable) {
					launchElem.errorMessage(t)
					return@launch
				} finally {
					elem.remove(loading)
				}

				elem.content = reason
			}
		}

		// show the commands
		launchElem.h1("Representative command").apply {
			title = "Cluster jobs may have many different commands. Shown here is one such command, as an example."
		}
		launchElem.div(classes = setOf("commands")) {
			content = clusterJobLog.representativeCommand
		}

		// show the params, if any
		launchElem.h1("Parameters")
		launchElem.div(classes = setOf("params")) {
			val params = clusterJobLog.commandParams
				?.takeIf { it.isNotBlank() }
			if (params != null) {

				// load the args for this job
				val loading = loading()
				AppScope.launch {
					val args = try {
						Args.fromJson(Services.jobs.getAllArgs())
					} finally {
						remove(loading)
					}

					// render the arg values
					table {
						for ((arg, value) in params.toArgValues(args).entries) {
							if (value == null) {
								continue
							}
							tr {
								td(arg.fullId, classes = setOf("name"))
								td(":")
								td(value.toString(), classes = setOf("value"))
							}
						}
					}
				}
			} else {
				content = "(none)"
			}
		}

		// show the full launch script
		launchElem.h1("Script")
		launchElem.div(classes = setOf("script")) {
			if (clusterJobLog.launchScript != null) {
				add(ScriptView.fromText(clusterJobLog.launchScript))
			} else {
				content = "(none)"
			}
		}

		return showLaunchTab
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
		logsElem.removeAll()
		if (navElem != null) {
			logsElem.add(navElem)
		}
		logsElem.add(logElem)
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
		streamElem.remove(logStreamer)
		elem.add(logStreamer)

		if (logStreamer.autoScroll.value) {
			logStreamer.scrollToBottom()
		}
	}

	private fun unpinStream() {

		pinned = false

		// move the log streamer from the bottom panel back to the popup
		logStreamer.parent?.let { panelBottom.remove(it) }
		streamElem.add(logStreamer)

		if (logStreamer.autoScroll.value) {
			logStreamer.scrollToBottom()
		}

		logStreamer.pinButton.visible = true
		win.show()
	}
}
