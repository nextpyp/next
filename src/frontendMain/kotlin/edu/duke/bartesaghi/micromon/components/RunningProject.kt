package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.views.*
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.html.link
import kotlin.js.Date


class RunningProject(val project: ProjectData) : Div(classes = setOf("running-project-grid")) {

	private val iconElem = iconStyled("fas fa-spinner fa-pulse", classes = setOf("status-icon"))
	private val nameElem = span(classes = setOf("name"))
	private val statusElem = div(classes = setOf("status"))
	private val timestampElem = Div(classes = setOf("timestamp"))
	private val jobElem = Div(classes = setOf("job"))

	private val jobs = HashMap<String,JobData>()
	private var connector: WebsocketConnector? = null

	var runningJobCount: Int = 0
		private set
	var waitingJobCount: Int = 0
		private set
	var onCountsChanged: (() -> Unit)? = null

	init {

		// finish layout
		statusElem.add(timestampElem)

		val connector = WebsocketConnector(RealTimeServices.project) { signaler, input, output ->

			// tell the server we want to listen to this project
			output.sendMessage(RealTimeC2S.ListenToProject(project.owner.id, project.projectId))

			// wait for the initial status
			init(input.receiveMessage<RealTimeS2C.ProjectStatus>())

			signaler.connected()

			// wait for responses from the server
			for (msg in input.messages()) {
				when (msg) {
					is RealTimeS2C.JobStart -> jobStart(msg)
					is RealTimeS2C.ProjectRunFinish -> runFinish(msg)
					else -> Unit
				}
			}
		}
		this.connector = connector
		// nameElem.add(WebsocketControl(connector))
		nameElem.link(project.numberedName, classes = setOf("running-link-grid") ).onGoToProject(project)

		// get the current status
		AppScope.launch {

			// load the jobs for this project
			jobs.putAll(
				Services.projects.getJobs(project.owner.id, project.projectId)
					.map { JobData.deserialize(it) }
					.associateBy { it.jobId }
			)

			connector.connect()
		}
	}

	fun close() {

		// close the realtime connection, if any
		// this will cancel the coroutine hosting the connection by throwing a CancellationException
		connector?.disconnect()
	}

	private fun jobName(jobId: String) =
		jobs[jobId]?.numberedName ?: "???"

	private fun init(msg: RealTimeS2C.ProjectStatus) {

		// get the most recent running run, if any
		val run = msg.recentRuns.lastOrNull { it.status == RunStatus.Running }
		if (run == null) {
			runningJobCount = 0
			waitingJobCount = 0
			onCountsChanged?.invoke()
			iconElem.icon = RunStatus.Canceled.toIcon()
			statusElem.div("(nothing currently running)")
			return
		}

		runningJobCount = run.jobs.count { it.status == RunStatus.Running }
		waitingJobCount = run.jobs.count { it.status == RunStatus.Waiting }
		onCountsChanged?.invoke()

		// briefly show the status
		timestampElem.content = "Run started at ${Date(run.timestamp).toLocaleString()}"
		iconElem.icon = run.status.toIcon()
		val jobData = run.jobs.lastOrNull { it.status == RunStatus.Running }
		if (jobData == null) {
			statusElem.div("(no jobs to show)")
			return
		}

		// show the job status
		val name  = jobName(jobData.jobId)
		jobElem.apply {
			iconStyled(jobData.status.toIcon(), classes = setOf("status-icon"))
			span(name)
		}
		statusElem.add(jobElem)
	}

	private fun jobStart(msg: RealTimeS2C.JobStart) {

		if (waitingJobCount > 0) waitingJobCount--
		runningJobCount = 1
		onCountsChanged?.invoke()

		// update the currently running job
		val name = jobName(msg.jobId)
		jobElem.apply {
			removeAll()
			iconStyled(RunStatus.Running.toIcon(), classes = setOf("status-icon"))
			span(name)
		}
	}

	private fun runFinish(msg: RealTimeS2C.ProjectRunFinish) {

		runningJobCount = 0
		waitingJobCount = 0
		onCountsChanged?.invoke()

		// set to finished state
		iconElem.icon = msg.status.toIcon()
		timestampElem.content = "Run finished at ${Date().toLocaleString()}"
		jobElem.removeAll()
	}
}
