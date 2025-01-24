package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.forms.ArgsForm
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.pyp.MicromonArgs
import edu.duke.bartesaghi.micromon.pyp.toArgValues
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.SingleParticleSessionView
import edu.duke.bartesaghi.micromon.views.TomographySessionView
import io.kvision.core.Component
import io.kvision.core.Container
import io.kvision.core.StringPair
import io.kvision.core.onEvent
import io.kvision.form.check.radioGroup
import io.kvision.form.select.select
import io.kvision.html.*
import io.kvision.modal.Confirm
import io.kvision.modal.Modal
import io.kvision.progress.Progress
import io.kvision.progress.progress
import io.kvision.progress.progressNumeric
import io.kvision.toast.Toast
import js.plotly.plot
import kotlinext.js.jsObject
import kotlinx.coroutines.delay
import kotlin.js.Date
import kotlin.math.min
import kotlin.reflect.KClass


class SessionOps(val session: SessionData) {

	val jobsMonitor = SessionJobsMonitor(session)
	val filesystemMonitor = SessionFilesystemMonitor()
	val transferMonitor = SessionTransferMonitor()
	val speedsMonitor = SessionSpeedsMonitor(session)
	val exportsMonitor = SessionExportsMonitor(session)

	var onClear: (SessionDaemon) -> Unit = {}

	private val daemonStatuses = SessionDaemon.values().associateWith {
		Span("(loading ...)")
	}

	private val cancelButton = Button("Cancel").apply {
		enabled = false
		title = "Cancels all daemons"
		onClick {
			cancel()
		}
	}

	private val startButtons = listOf(SessionDaemon.mainDaemon()).associateWith { daemon ->
		Button("Start").apply {
			enabled = false
			onClick {
				startDaemon(daemon)
			}
		}
	}

	private val stopButtons = SessionDaemon.values().associateWith { daemon ->
		Button("Stop").apply {
			enabled = false
			onClick {
				stopDaemon(daemon)
			}
		}
	}

	private val restartButtons = SessionDaemon.subDaemons().associateWith { daemon ->
		Button("Restart").apply {
			enabled = false
			onClick {
				restartDaemon(daemon)
			}
		}
	}

	private val clearButtons = SessionDaemon.subDaemons().associateWith { daemon ->
		Button("Clear").apply {
			enabled = false
			onClick {
				clearDaemon(daemon)
			}
		}
	}

	private val logsButtons = SessionDaemon.values().associateWith { daemon ->
		Button("Logs").apply {
			onClick {
				showDaemonLogs(daemon)
			}
		}
	}

	fun init(msg: RealTimeS2C.SessionStatus) {

		for (daemon in SessionDaemon.values()) {
			daemonStatuses.getValue(daemon).content = when(msg.isRunning(daemon)) {
				true -> "Running"
				false -> "Not running"
			}
			stopButtons.getValue(daemon).enabled = msg.isRunning(daemon)
			startButtons[daemon]?.enabled = !msg.isRunning(daemon)
			restartButtons[daemon]?.enabled = msg.isRunning(daemon)
			clearButtons[daemon]?.enabled = msg.isRunning(daemon)
		}

		updateCancelButton()

		jobsMonitor.init(msg.jobsRunning)
		speedsMonitor.update()
	}

	fun show(elem: Container) {

		elem.addCssClass("ops")

		// layout the tab
		elem.div {

			table(classes = setOf("controls")) {
				thead {
					tr {
						td("Daemon", classes = setOf("daemon"))
						td("Status", classes = setOf("status"))
						td(
							if (SessionPermission.Write in session.permissions) {
								"Controls"
							} else if (SessionPermission.Read in session.permissions) {
								"Logs"
							} else {
								"Nothing"
							}
						)
						td()
						td()
						td()
					}
				}
				tbody {
					SessionDaemon.mainDaemon().let { daemon ->
						tr {
							td(daemon.label)
							td {
								add(daemonStatuses.getValue(daemon))
							}
							td(classes = setOf("buttons")) {
								if (SessionPermission.Write in session.permissions) {
									add(startButtons.getValue(daemon))
								}
							}
							td {
								if (SessionPermission.Write in session.permissions) {
									add(stopButtons.getValue(daemon))
								}
							}
							td {
								add(logsButtons.getValue(daemon))
							}
							td {
								if (SessionPermission.Write in session.permissions) {
									add(cancelButton)
								}
							}
						}
					}
					SessionDaemon.subDaemons().forEach { daemon ->
						tr {
							td(daemon.label)
							td {
								add(daemonStatuses.getValue(daemon))
							}
							td(classes = setOf("buttons")) {
								if (SessionPermission.Write in session.permissions) {
									add(restartButtons.getValue(daemon))
									add(clearButtons.getValue(daemon))
								}
							}
							td {
								if (SessionPermission.Write in session.permissions) {
									add(stopButtons.getValue(daemon))
								}
							}
							td {
								add(logsButtons.getValue(daemon))
							}
						}
					}
				}
			}

			div {
				h1("Jobs")
				add(jobsMonitor)
			}

			div {
				h1("Exports")
				add(exportsMonitor)
			}

			div {
				h1("Storage")
				add(filesystemMonitor)
			}

			div {
				h1("Transfer Speeds")
				add(speedsMonitor)
			}

			div {
				h1("File Transfers")
				add(transferMonitor)
			}
		}
	}

	fun daemonSubmitted(msg: RealTimeS2C.SessionDaemonSubmitted) {
		daemonStatuses.getValue(msg.daemon).content = "Waiting to start ..."
	}

	fun daemonStarted(msg: RealTimeS2C.SessionDaemonStarted) {
		daemonStatuses.getValue(msg.daemon).content = "Running"
		stopButtons.getValue(msg.daemon).enabled = true
		restartButtons[msg.daemon]?.enabled = true
		clearButtons[msg.daemon]?.enabled = true
		updateCancelButton()
	}

	fun daemonFinished(msg: RealTimeS2C.SessionDaemonFinished) {
		daemonStatuses.getValue(msg.daemon).content = "Finished"
		startButtons[msg.daemon]?.enabled = true
		restartButtons[msg.daemon]?.enabled = false
		clearButtons[msg.daemon]?.enabled = false
		stopButtons.getValue(msg.daemon).enabled = false
		updateCancelButton()
	}

	private fun updateCancelButton() {
		cancelButton.enabled = SessionDaemon.values().any { daemon ->
			stopButtons.getValue(daemon).enabled
		}
	}

	fun cancel() {

		// update the UI
		cancelButton.enabled = false
		for (daemon in SessionDaemon.values()) {
			if (stopButtons.getValue(daemon).enabled) {
				daemonStatuses.getValue(daemon).content = "Canceling ..."
				startButtons[daemon]?.enabled = false
				restartButtons[daemon]?.enabled = false
				clearButtons[daemon]?.enabled = false
				stopButtons.getValue(daemon).enabled = false
			}
		}

		AppScope.launch {
			try {
				Services.sessions.cancel(session.sessionId)
			} catch (t: Throwable) {
				t.reportError()
				Toast.error("Failed to cancel session: " + t.message)
				return@launch
			}
		}
	}

	fun startDaemon(daemon: SessionDaemon) {

		// can only start the main daemon
		if (!daemon.isMainDaemon) {
			return
		}

		AppScope.launch {

			// validate the session arguments
			val args = when (session) {
				is SingleParticleSessionData -> SingleParticleSessionView.pypArgs.get()
				is TomographySessionData -> TomographySessionView.pypArgs.get()
			}
			val argValues = session.newestArgs?.values?.toArgValues(args)

			// just check for missing required values for now
			val argsValid = argValues != null && args.args.none { arg ->
				arg.required && argValues[arg] == null
			}
			if (!argsValid) {
				Toast.error("Can't start session, missing some required arguments")
				return@launch
			}

			// update the UI
			daemonStatuses.getValue(daemon).content = "Starting ..."
			startButtons.getValue(daemon).enabled = false
			stopButtons.getValue(daemon).enabled = true

			try {
				Services.sessions.start(session.sessionId, daemon)
			} catch (t: Throwable) {
				t.reportError()
				Toast.error("Failed to start session: " + t.message)
				return@launch
			}
		}
	}

	fun restartDaemon(daemon: SessionDaemon) {

		// can only restart the sub-daemons
		if (!daemon.isSubDaemon) {
			return
		}

		// show some kind of feedback to the user
		AppScope.launch {
			val button = restartButtons.getValue(daemon)
			button.enabled = false
			delay(2000)
			button.enabled = true
		}

		AppScope.launch {
			try {
				Services.sessions.restart(session.sessionId, daemon)
			} catch (t: Throwable) {
				t.reportError()
				Toast.error("Failed to restart session: " + t.message)
				return@launch
			}
		}
	}

	fun clearDaemon(daemon: SessionDaemon) {

		// can only clear the sub-daemons
		if (!daemon.isSubDaemon) {
			return
		}

		// show some kind of feedback to the user
		AppScope.launch {
			val button = clearButtons.getValue(daemon)
			button.enabled = false
			delay(2000)
			button.enabled = true
		}

		AppScope.launch {
			try {
				Services.sessions.clear(session.sessionId, daemon)
			} catch (t: Throwable) {
				t.reportError()
				Toast.error("Failed to clear session: " + t.message)
				return@launch
			}

			// if that worked, bubble up the event
			onClear(daemon)
		}
	}

	fun stopDaemon(daemon: SessionDaemon) {

		// update the UI
		daemonStatuses.getValue(daemon).content = "Stopping ..."
		startButtons[daemon]?.enabled = false
		restartButtons[daemon]?.enabled = false
		clearButtons[daemon]?.enabled = false
		stopButtons.getValue(daemon).enabled = false

		AppScope.launch {
			try {
				Services.sessions.stop(session.sessionId, daemon)
			} catch (t: Throwable) {
				t.reportError()
				Toast.error("Failed to stop session: " + t.message)
				return@launch
			}
		}
	}

	fun showDaemonLogs(daemon: SessionDaemon) {

		// show the logs in a popup window
		val win = Modal(
			caption = "Logs for Session: ${session.numberedName}",
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup", "max-height-dialog", "session-logs-popup")
		)

		val showButton = Button("Show").apply {
			enabled = false
			win.addButton(this)
		}

		fun SessionLogData.label(): String =
			Date(timestamp).toLocaleString()

		win.show()

		AppScope.launch {

			val loading = win.loading("Loading logs ...")
			val logs = try {
				delayAtLeast(200) {
					Services.sessions.logs(session.sessionId, daemon)
						.logs
						.sortedByDescending { it.timestamp }
				}
			} catch (t: Throwable) {
				win.errorMessage(t)
				return@launch
			} finally {
				win.remove(loading)
			}

			if (logs.isEmpty()) {
				win.div("No logs to show", classes = setOf("empty"))
				return@launch
			}

			// show a picker for the logs
			val picker = win.radioGroup(
				options = logs
					.map { log ->
						StringPair(log.clusterJobId, log.label())
					},
				label = "Started at"
			)
			win.div {
				add(picker)
			}

			// wire up events
			showButton.onClick e@{
				win.hide()
				val log = logs.find { it.clusterJobId == picker.value }
					?: return@e
				ClusterJobLogViewer(log.clusterJobId, "Session started at ${log.label()}")
			}

			// init form state
			showButton.enabled = true
			picker.value = logs.first().clusterJobId
		}
	}

	fun export(request: SessionExportRequest) {

		val display = request.display()

		val win = Modal(
			caption = "Export ${display.name}: ${display.detail}",
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup", "args-form-popup")
		)

		win.add(display.elem)

		// add slurm settings
		win.h2("SLURM Parameters")
		val slurmForm = ArgsForm(MicromonArgs.args, emptyList(), true)
			.also { win.add(it) }

		val exportButton = Button("Export", icon = "fas fa-file-export")
		exportButton.onClick {

			if (!display.validate()) {
				return@onClick
			}

			// TODO: do we need to collect additional args here? eg
			// request.args = display.collectArgs()

			// collect the SLURM arg values
			val slurmValues = slurmForm.value ?: ""

			// show a success view in the popup
			win.removeAll()
			win.removeAllButtons()
			win.h2("Export started")
			win.div("The export has started. You can track progress and view the results on the Ops tab.")
			win.addButton(Button("Ok").apply {
				onClick { win.hide() }
			})

			AppScope.launch {
				Services.sessions.export(session.sessionId, request.serialize(), slurmValues)
			}
		}
		win.addButton(exportButton)

		win.show()
	}
}



class SessionFilesystemMonitor : Div(classes = setOf("session-filesystem-monitor")) {

	init {
		div("Initializing ...", classes = setOf("empty"))
	}

	fun update(msg: RealTimeS2C.SessionFilesystems) {

		removeAll()

		table(classes = setOf("filesystems")) {
			thead {
				tr {
					td()
					td("Filesystem")
					td("Type")
					td("Total")
					td("Available")
					td("Used")
					td()
				}
			}
			tbody {
				for (fs in msg.filesystems) {
					tr {
						td {
							icon("fas fa-folder")
						}
						td(fs.path) // filesystem
						td(fs.type)
						td(fs.bytes.toBytesString(), align=Align.RIGHT) // total
						td(fs.bytesAvailable.toBytesString(), align=Align.RIGHT) // available
						td("${fs.used.let { it*100.0 }.toFixed(1)}%", align=Align.RIGHT) // used
						td(classes = setOf("bar")) {
							progress(0, 100) {
								progressNumeric((fs.used*100.0).toInt())
							}
						}
					}
				}
			}
		}
	}
}

class SessionJobsMonitor(val session: SessionData) : Div(classes = setOf("session-jobs-monitor")) {

	private class Info(
		val jobId: String,
		val name: String,
		val size: Int,
		initialStatus: RunStatus
	) {

		val statusElem = Span(initialStatus.name)
		val elem = Div().apply {
			span("Name: $name, Size: $size, Status: ")
			add(statusElem)
		}

		var status: RunStatus = initialStatus
			get() = field
			set(value) {
				field = value
				statusElem.content = value.name
			}
	}

	private val infos = HashMap<String,Info>()
	private val emptyElem = div("Initializing ...", classes = setOf("empty"))
	private val jobsElem = div(classes = setOf("jobs"))
	private val buttonsElem = div(classes = setOf("buttons"))
	private val logsButton = buttonsElem.button("Logs")
		.onClick {
			showLogs()
		}

	fun init(jobs: List<RealTimeS2C.SessionRunningJob>) {

		infos.clear()
		jobsElem.removeAll()

		for (job in jobs) {

			val info = Info(
				job.jobId,
				job.name,
				job.size,
				job.status
			)
			infos[info.jobId] = info

			jobsElem.add(info.elem)
		}

		updateEmpty()
	}

	fun submitted(job: RealTimeS2C.SessionJobSubmitted) {

		val info = Info(
			job.jobId,
			job.name,
			job.size,
			RunStatus.Waiting
		)
		infos[info.jobId] = info

		jobsElem.add(info.elem)

		updateEmpty()
	}

	fun started(job: RealTimeS2C.SessionJobStarted) {

		val info = infos[job.jobId] ?: return
		info.status = RunStatus.Running
	}

	fun finished(job: RealTimeS2C.SessionJobFinished) {

		val info = infos[job.jobId] ?: return

		jobsElem.remove(info.elem)
		infos.remove(job.jobId)

		updateEmpty()
	}

	private fun updateEmpty() {
		emptyElem.content = if (infos.isEmpty()) {
			"No jobs are currently running"
		} else {
			""
		}
	}

	private fun showLogs() {

		// show the logs in a popup window
		val win = Modal(
			caption = "Session Job Logs",
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup", "max-height-dialog", "session-jobs-logs-popup")
		)
		win.show()

		val showButton = Button("Show").apply {
			enabled = false
			win.addButton(this)
		}

		fun SessionJobLogData.label(): String =
			Date(timestamp).toLocaleString()

		AppScope.launch {

			val loading = win.loading("Loading logs ...")
			val jobsLogs = try {
				Services.sessions.jobsLogs(session.sessionId)
			} catch (t: Throwable) {
				win.errorMessage(t)
				return@launch
			} finally {
				win.remove(loading)
			}

			if (jobsLogs.jobs.isEmpty()) {
				win.div("No logs to show", classes = setOf("empty"))
				return@launch
			}

			// find the unique job names
			val jobNames = jobsLogs.jobs
				.map { it.name }
				.toSet()
				.toList()
				.sorted()

			val nameSelect = win.select(
				options = jobNames.map { StringPair(it, it) },
				label = "Job"
			)

			// show a picker for the logs
			val picker = win.radioGroup(
				label = "Started at"
			)
			win.div {
				add(picker)
			}

			fun showOptions() {
				val logs = jobsLogs.jobs
					.filter { it.name == nameSelect.value }
					.sortedByDescending { it.timestamp }
				if (logs.isEmpty()) {
					picker.options = emptyList()
					return
				}
				picker.options = logs
					.map { log ->
						StringPair(log.clusterJobId, log.label())
					}
				picker.value = logs.first().clusterJobId
			}

			// wire up events
			nameSelect.onEvent {
				change = {
					showOptions()
				}
			}
			showButton.onClick e@{
				win.hide()
				val log = jobsLogs.jobs.find { it.clusterJobId == picker.value }
					?: return@e
				ClusterJobLogViewer(log.clusterJobId, "Session job started at ${log.label()}")
			}

			// init form state
			showButton.enabled = true
			nameSelect.value = jobNames.firstOrNull()
			showOptions()
		}
	}
}


class SessionTransferMonitor : Div(classes = setOf("session-transfer-monitor")) {

	private class Info(
		val filename: String,
		val bytesTotal: Long
	) {

		private val progress = Progress(0, 100) {
			progressNumeric(0)
		}

		val elem = Tr {
			td(filename, classes = setOf("file"))
			td(bytesTotal.toBytesString(), classes = setOf("size"))
			td(classes = setOf("bar")) {
				add(progress)
			}
		}

		var bytesTransferred: Long = 0
			set(value) {
				field = value

				// update the progress bar
				progress.getFirstProgressBar()?.value = (100f*value.toFloat()/bytesTotal.toFloat()).toInt()
			}
	}

	private val waitingCount = Span()
	private val transferringCount = Span()
	private val finishedCount = Span()
	private val transferRows = Tbody()
	private val emptyElem = Div("Initializing ...", classes = setOf("empty"))

	private inner class Counts {

		var waiting: Int = 0
			set(value) {
				field = value
				waitingCount.content = value.toString()
			}

		var transferring: Int = 0
			set(value) {
				field = value
				transferringCount.content = value.toString()
			}

		var finished: Int = 0
			set(value) {
				field = value
				finishedCount.content = value.toString()
			}
	}
	private val counts = Counts()

	private val waitingFiles = HashSet<String>()
	private val infos = HashMap<String,Info>()

	init {

		// show the file counts in a table
		table(classes = setOf("counts")) {
			thead {
				tr {
					td("Waiting")
					td("Transferring")
					td("Finished")
				}
			}
			tbody {
				tr {
					td {
						add(this@SessionTransferMonitor.waitingCount)
					}
					td {
						add(this@SessionTransferMonitor.transferringCount)
					}
					td {
						add(this@SessionTransferMonitor.finishedCount)
					}
				}
			}
		}

		// show the transferring files
		table(classes = setOf("transfers")) {
			thead {
				tr {
					td("File")
					td("Size")
					td("Progress")
				}
			}
			add(this@SessionTransferMonitor.transferRows)
		}

		add(emptyElem)
	}

	fun init(msg: RealTimeS2C.SessionTransferInit) {

		// init the counts
		val waiting = msg.files.filter { it.bytesTransfered == 0L }
		counts.waiting = waiting.size
		val transferring = msg.files.filter { it.bytesTransfered != 0L && it.bytesTransfered < it.bytesTotal }
		counts.transferring = transferring.size
		counts.finished = msg.files.count { it.bytesTransfered == it.bytesTotal }

		waitingFiles.clear()
		waitingFiles.addAll(waiting.map { it.filename })

		// show the transfers
		infos.clear()
		transferRows.removeAll()
		for (file in transferring) {
			val info = Info(file.filename, file.bytesTotal)
			infos[info.filename] = info
			transferRows.add(info.elem)
		}

		updateEmpty()
	}

	fun waiting(msg: RealTimeS2C.SessionTransferWaiting) {
		waitingFiles.add(msg.filename)
		counts.waiting = waitingFiles.size
	}

	fun started(msg: RealTimeS2C.SessionTransferStarted) {

		waitingFiles.remove(msg.filename)

		val info = Info(msg.filename,  msg.bytesTotal)
		infos[info.filename] = info
		transferRows.add(info.elem)

		counts.waiting = waitingFiles.size
		counts.transferring = infos.size

		updateEmpty()
	}

	fun progress(msg: RealTimeS2C.SessionTransferProgress) {

		val info = infos[msg.filename] ?: return
		info.bytesTransferred = msg.bytesTransferred
	}

	fun finished(msg: RealTimeS2C.SessionTransferFinished) {

		val info = infos[msg.filename] ?: return

		info.bytesTransferred = info.bytesTotal

		// cleanup the entry after a bit
		AppScope.launch {

			delay(5000)

			infos.remove(info.filename)
			transferRows.remove(info.elem)

			counts.transferring = infos.size
			counts.finished += 1
		}

		updateEmpty()
	}

	private fun updateEmpty() {
		emptyElem.content = if (infos.isEmpty()) {
			"No files are currently transferring"
		} else {
			""
		}
	}
}


class SessionSpeedsMonitor(val session: SessionData) : Div(classes = setOf("session-speeds-monitor")) {

	init {
		div("Initializing ...", classes = setOf("empty"))
	}

	companion object {
		const val updateDelaySeconds = 2
	}

	private var waitingToUpdate = false

	fun transferFinished() {

		// the file transfer is finished, but the speeds might not have been updated yet
		// so wait a bit before reloading them
		// but if lots of transfers finish at once, try to batch calls together so we don't spam the backend

		// drop this update if one is already scheduled
		if (waitingToUpdate) {
			return
		}

		// otherwise, schedule another update
		waitingToUpdate = true
		AppScope.launch {
			delay(updateDelaySeconds*1000L)
			try {
				update()
			} finally {
				waitingToUpdate = false
			}
		}
	}

	fun update() {

		AppScope.launch {

			val speeds = try {
				Services.sessions.speeds(session.sessionId)
			} catch (t: Throwable) {
				t.reportError("can't get session speeds")
				return@launch
			}

			removeAll()

			if (speeds.transfers.isEmpty()) {
				div("No speeds to show yet", classes = setOf("empty"))
				return@launch
			}

			// filter outliers from the speeds
			val gbpsMax = (speeds.transfers.median() ?: 1.0)*4
			val filteredGbps = speeds.transfers.filter { it <= gbpsMax }
			val outliers = speeds.transfers.filter { it > gbpsMax }

			// get the average speed
			val avgGbps = speeds.transfers.average()

			// show some stats
			val statMsgs = mutableListOf("Average speed = ${avgGbps.toFixed(1)} Gpbs")
			if (outliers.isNotEmpty()) {
				statMsgs.add("${outliers.size} outliers above ${gbpsMax.toFixed(1)} Gbps")
			}
			div(statMsgs.joinToString(", "))

			// show the speeds in plot
			plot(
				jsObject {
					y = filteredGbps.toTypedArray()
					mode = "markers"
					marker = jsObject {
						size = 6
						color = IntArray(filteredGbps.size) { it }
					}
					hovertemplate = "%{y} Gbps"
					type = "scattergl"
				},
				layout = jsObject {
					xaxis = jsObject {
						showspikes = false
						showticklabels = false
						showgrid = false
					}
					yaxis = jsObject {
						title = "Speed (Gbps)"
						titlefont = jsObject {
							size = 12
						}
						automargin = true
					}
					showlegend = false
					margin = jsObject {
						r = 0
						b = 0
						t = 20
					}
					hovermode = "closest"
					shapes = arrayOf(
						// show average line
						jsObject {
							type = "line"
							x0 = 0
							x1 = speeds.transfers.size - 1
							y0 = avgGbps
							y1 = avgGbps
							line = jsObject {
								width = 3
							}
						}
					)
				},
				config = jsObject {
					responsive = true
				},
				classes = setOf("speeds-plot")
			)
		}
	}
}


private class ExportRequestDisplay(
	val name: String,
	val detail: String,
	val elem: Component,
	val validate: () -> Boolean,
	val show: (SessionData, SessionExportResult.Succeeded.Output) -> Unit
)

private val exportRequestDisplays = mapOf<KClass<out SessionExportRequest>,(SessionExportRequest) -> ExportRequestDisplay>(

	SessionExportRequest.Filter::class to { request ->
		request as SessionExportRequest.Filter
		ExportRequestDisplay(
			name = "Filter",
			detail = request.name,
			elem = Div(), // TODO: do we need to show UI to collect args here?
			validate = {
				true
				// TODO: need a function here to collect args? eg
				//fun collectArgs(): Filter.Args
			},
			show = { session, out ->
				out as SessionExportResult.Succeeded.Output.Filter
				val path = Paths.join(session.path, out.path)
				PathPopup.show("Path to exported Filter: ${request.name}", path)
			}
		)
	}
)

private fun SessionExportRequest.display(): ExportRequestDisplay {
	val factory = exportRequestDisplays[this::class]
		?: throw NoSuchElementException("no display defined for ${this::class.simpleName}")
	return factory(this)
}


class SessionExportsMonitor(val session: SessionData) : Div(classes = setOf("session-exports-monitor")) {

	companion object {
		const val TopSize = 3
	}

	private val elems = HashMap<String,ExportElem>()

	inner class ExportElem(export: SessionExportData) : Div(classes = setOf("export")) {

		var export: SessionExportData = export
			set(value) {

				// make sure it's the same export, just in case
				if (field.exportId != value.exportId) {
					throw Error("Can't update with different export")
				}

				field = value
			}

		fun redraw() {

			val monitor = this@SessionExportsMonitor

			removeAll()

			// show a logs button, or a waiting spinner
			val clusterJobId = export.clusterJobId
			if (clusterJobId != null) {
				button("", icon = "far fa-file-alt").apply {
					title = "Show logs"
					onClick {
						ClusterJobLogViewer(clusterJobId, "Export")
					}
				}
			} else {
				icon("fas fa-spinner fa-pulse")
			}

			val display = export.getRequest().display()

			// show the result, if any
			when (val result = export.getResult()) {

				null ->
					button("", icon = "fas fa-ban", classes = setOf("cancel")).apply {
						title = "Cancel export"
						onClick {
							cancel(this)
						}
					}

				is SessionExportResult.Canceled ->
					iconStyled("fas fa-ban", classes = setOf("icon")).apply {
						title = "This export was canceled"
					}

				is SessionExportResult.Failed ->
					iconStyled("fas fa-exclamation-triangle", classes = setOf("icon")).apply {
						title = "The export failed: ${result.reason}"
					}

				is SessionExportResult.Succeeded ->
					button("", icon = "fas fa-eye").apply {
						title = "Show the result"
						onClick {
							display.show(monitor.session, result.output)
						}
					}
			}

			// show a name for the export
			span("${display.name}: ${display.detail}", classes = setOf("name"))

			// show the timestamp
			span("on ${Date(export.created).toLocaleString()}")
		}

		private fun cancel(button: Button) {

			val display = export.getRequest().display()

			// confirm the cancelation
			Confirm.show(
				"Really cancel this export?",
				"Are you sure you want to cancel ${display.name}: ${display.detail}"
			) {

				// disable the button
				button.enabled = false
				button.icon = "fas fa-spinner fa-pulse"

				AppScope.launch {
					Services.sessions.cancelExport(export.exportId)
				}
			}
		}
	}

	init {
		redraw()
	}

	fun setData(msg: RealTimeS2C.SessionSmallData) {

		// load the initial exports
		for (export in msg.exports) {
			ExportElem(export)
				.also { elems[it.export.exportId] = it }
		}

		redraw()
	}

	fun update(msg: RealTimeS2C.SessionExport) {

		elems[msg.export.exportId]

			// if the elem already exists, update it
			?.let { it.export = msg.export }

			// otherwise, make a new one
			?: ExportElem(msg.export)
				.also { elems[it.export.exportId] = it }

		redraw()
	}

	private fun redraw() {

		removeAll()

		if (elems.isEmpty()) {
			div("Nothing exported yet", classes = setOf("empty", "spaced"))
			return
		}

		// show the newest N exports
		val newestElems = elems.values
			.sortedByDescending { it.export.created }
			.let { it.subList(0, min(it.size, TopSize)) }

		for (elem in newestElems) {
			elem.redraw()
			add(elem)
		}

		// add a button to show all the exports if needed
		if (elems.size > TopSize) {
			div {
				button("Show More").onClick {
					this@SessionExportsMonitor.showAll()
				}
			}
		}
	}

	private fun showAll() {

		val win = Modal(
			caption = "Session Exports",
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup", "max-height-dialog", "session-exports-monitor")
		)

		// get the not-newest N exports
		val notNewestElems = elems.values
			.sortedByDescending { it.export.created }
			.let { it.subList(min(it.size, TopSize), it.size) }

		for (elem in notNewestElems) {
			elem.redraw()
			win.add(elem)
		}

		win.show()
	}
}
