package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.IconStyled
import edu.duke.bartesaghi.micromon.components.forms.enableClickIf
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Container
import io.kvision.html.*
import io.kvision.modal.Confirm
import io.kvision.modal.Modal
import io.kvision.moment.Moment
import io.kvision.toast.Toast
import kotlinext.js.jsObject
import kotlin.js.Date


/**
 * Provides a UI for monitoring cluster jobs from e.g. SLURM
 */
class JobsMonitor(
	val panelBottom: Container,
	val project: ProjectData,
	val elem: Container,
	val getJobs: () -> Iterable<JobData>
) {

	companion object {
		const val IconWaiting = "fas fa-clock"
		const val IconRunning = "fas fa-cog fa-spin"
		const val IconSucceeded = "fas fa-check-circle"
		const val IconFailed = "fas fa-exclamation-triangle"
		const val IconCanceled = "fas fa-ban"
		const val DefaultClusterJobName = "Job"
		const val DeletedLabel = "(block was deleted)"
		const val DeletedLogsText = "These logs are no longer available"
	}

	private inner class RunEntry(
		val runId: Int,
		val timestamp: Date
	) {

		val jobEntries = HashMap<String,JobEntry>()

		val statusElem = IconStyled("", classes = setOf("status-icon"))
		val labelElem = Span("Run", classes = setOf("label"))
		val cancelButton = Button(
			"",
			icon = "fas fa-ban",
			classes = setOf("cancel")
		).apply {
			title = "Cancel this run"
			enableClickIf(project.canWrite()) { event ->
				cancel()
				// prevent the disclosure from collapsing
				event.stopPropagation()
			}
		}
		val jobsElem = Div()

		val elem = Disclosure(
			label = {
				add(statusElem)
				add(labelElem)
				add(cancelButton)
			},
			disclosed = {
				add(jobsElem)
			},
			classes = setOf("run")
		)

		fun init(msg: RealTimeS2C.ProjectRunInit) {

			val jobs = getJobs().associateBy { it.jobId }
			for (jobId in msg.jobIds) {

				// start a new job entry
				val entry = JobEntry(jobId, jobs[jobId])

				jobEntries[jobId] = entry
				jobsElem.add(entry.elem)
			}
		}

		fun init(data: ProjectRunData) {

			statusElem.icon = data.status.toIcon()
			cancelable = data.status in listOf(RunStatus.Waiting, RunStatus.Running)

			val jobs = getJobs().associateBy { it.jobId }
			for (jobData in data.jobs) {
				val entry = JobEntry(jobData.jobId, jobs[jobData.jobId])
				jobEntries[jobData.jobId] = entry
				jobsElem.add(entry.elem)
				entry.init(jobData)
			}
		}

		init {
			statusElem.icon = IconWaiting
		}

		fun start(msg: RealTimeS2C.ProjectRunStart) {
			statusElem.icon = IconRunning
		}

		fun finish(msg: RealTimeS2C.ProjectRunFinish) {
			statusElem.icon = msg.status.toIcon()
			cancelable = false
		}

		var cancelable: Boolean
			get() = cancelButton.visible
			set(value) { cancelButton.visible = value }

		fun cancel() {

			if (!cancelable) {
				return
			}

			// confirm the cancelation
			Confirm.show(
				"Really cancel these jobs?",
				"Are you sure you want to cancel these jobs?"
			) {

				// disable the button
				cancelButton.enabled = false
				cancelButton.icon = "fas fa-spinner fa-pulse"

				// cancel the run
				AppScope.launch {
					Services.projects.cancelRun(project.owner.id, project.projectId, runId)
				}
			}
		}

		fun jobDeleted(jobId: String): Boolean {

			val job = jobEntries.remove(jobId)
				?: return false

			jobsElem.remove(job.elem)
			return true
		}

		inner class JobEntry(
			val jobId: String,
			job: JobData?
		) {

			var job: JobData? = job
				private set

			private val clusterJobEntries = HashMap<String,ClusterJobEntry>()

			val statusElem = IconStyled("", classes = setOf("status-icon"))
			val clusterJobsElem = Div()

			val nameElem = Span(job?.numberedName ?: DeletedLabel, classes = setOf("name"))

			val elem = Disclosure(
				label = {
					add(statusElem)
					add(nameElem)
				},
				disclosed = {
					add(clusterJobsElem)
				},
				classes = setOf("job")
			)

			private fun updateDisclosureIcon() {

				// only show the disclosure icon if we have any cluster jobs
				elem.iconVisible = clusterJobEntries.isNotEmpty()
			}

			fun init(data: JobRunData) {

				statusElem.icon = data.status.toIcon()

				// init cluster jobs
				for (cjob in data.clusterJobs) {
					val entry = ClusterJobEntry(
						cjob.clusterJobId,
						cjob.webName ?: DefaultClusterJobName,
						cjob.status,
						cjob.arrayInfo?.size
					)
					if (cjob.arrayInfo != null) {
						entry.numStarted = cjob.arrayInfo.started
						entry.arrayFinished(cjob.arrayInfo.ended, cjob.arrayInfo.canceled, cjob.arrayInfo.failed)
					}
					clusterJobEntries[cjob.clusterJobId] = entry
					clusterJobsElem.add(entry.elem)
				}

				updateDisclosureIcon()
			}

			fun submitClusterJob(msg: RealTimeS2C.ClusterJobSubmit) {
				val entry = ClusterJobEntry(
					msg.clusterJobId,
					msg.clusterJobName ?: DefaultClusterJobName,
					RunStatus.Waiting,
					msg.arraySize
				)
				clusterJobEntries[msg.clusterJobId] = entry
				clusterJobsElem.add(entry.elem)
				updateDisclosureIcon()
			}

			fun startClusterJob(msg: RealTimeS2C.ClusterJobStart) {
				clusterJobEntries[msg.clusterJobId]?.status = RunStatus.Running
			}

			fun startClusterJobArray(msg: RealTimeS2C.ClusterJobArrayStart) {
				clusterJobEntries[msg.clusterJobId]?.numStarted = msg.numStarted
			}

			fun finishClusterJobArray(msg: RealTimeS2C.ClusterJobArrayEnd) {
				clusterJobEntries[msg.clusterJobId]?.arrayFinished(msg.numEnded, msg.numCanceled, msg.numFailed)
			}

			fun finishClusterJob(msg: RealTimeS2C.ClusterJobEnd) {
				clusterJobEntries[msg.clusterJobId]?.let { job ->

					job.status = when (msg.resultType) {
						ClusterJobResultType.Success -> RunStatus.Succeeded
						ClusterJobResultType.Failure -> RunStatus.Failed
						ClusterJobResultType.Canceled -> RunStatus.Canceled
					}

					// HACKHACK: sometimes we don't get finish events for each array job... I guess they get lost somehow
					// but since the whole cluster job is done, just mark all the array jobs done too
					job.arrayFinished(job.numStarted, job.numCanceled, job.numFailed)
				}
			}

			init {
				statusElem.icon = IconWaiting
			}

			fun start(msg: RealTimeS2C.JobStart) {
				statusElem.icon = IconRunning
			}

			fun finish(msg: RealTimeS2C.JobFinish) {
				statusElem.icon = msg.status.toIcon()

				if (msg.errorMessage != null) {
					// add a button to show the error
					elem.labelElem.button("", icon = IconFailed).apply {
						title = "View job launch error message"
						onClick {
							val win = Modal(
								caption = "Job launch failed",
								escape = true,
								closeButton = true,
								classes = setOf("dashboard-popup")
							)
							win.div(msg.errorMessage)
							win.show()
						}
					}
				}
			}

			fun highlight() {

				// make sure the job entry is showing
				// ie, the ancestors are all disclosed
				this@JobsMonitor.periodElems.values
					.find { this@RunEntry.elem in it.disclosedElem.getChildren() }
					?.open = true
				this@RunEntry.elem.open = true
				elem.open = true

				elem.flash()
			}

			inner class ClusterJobEntry(
				val clusterJobId: String,
				val name: String,
				status: RunStatus,
				val arraySize: Int?
			) {

				var status = status
					set (value) {
						field = value
						updateStatus()
					}

				var numStarted = 0
					set (value) {
						field = value
						updateCount()
					}

				var numEnded = 0
					private set
				var numCanceled = 0
					private set
				var numFailed = 0
					private set

				fun arrayFinished(numEnded: Int, numCanceled: Int, numFailed: Int) {
					this.numEnded = numEnded
					this.numCanceled = numCanceled
					this.numFailed = numFailed
					updateCount()
				}

				val statusElem = IconStyled("")
				val countElem = Div(null, classes = setOf("count"))

				val logsButton = Button(
					"",
					icon = "far fa-file-alt"
				).apply {
					val job = job
					if (job != null) {
						title = "Show the logs"
						onClick {
							ClusterJobLogViewer(clusterJobId, name, panelBottom)
						}
					} else {
						title = DeletedLogsText
						enabled = false
					}
				}

				val elem = Div(classes = setOf("cluster-job")) {
					div(classes = setOf("label-row")) {
						div(classes = setOf("label")) {
							add(statusElem)
							span(name, classes = setOf("name"))
						}
						add(logsButton)
					}
					add(countElem)
				}

				private fun updateStatus() {
					statusElem.icon = status.toIcon()
				}

				private fun updateCount() {

					// only for array jobs
					if (arraySize == null) {
						return
					}

					countElem.run {
						removeAll()

						fun group(num: Int, icon: String) {
							span(classes = setOf("group")) {
								iconStyled(icon)
								span("$num", classes = setOf("label"))
							}
						}

						val numRunning = numStarted - numEnded
						var numWaiting = arraySize - numStarted
						val numSucceeded = numEnded - numFailed - numCanceled
						var numCanceled = numCanceled

						// HACKHACK: if the job was canceled, then all the waiting jobs are canceled too
						if (status == RunStatus.Canceled) {
							numCanceled += numWaiting
							numWaiting = 0
						}

						if (numWaiting > 0) {
							group(numWaiting, IconWaiting)
						}
						if (numRunning > 0) {
							group(numRunning, IconRunning)
						}
						if (numSucceeded > 0) {
							group(numSucceeded, IconSucceeded)
						}
						if (numCanceled > 0) {
							group(numCanceled, IconCanceled)
						}
						if (numFailed > 0) {
							group(numFailed, IconFailed)
						}
					}
				}

				init {
					updateStatus()
				}

			} // ClusterJobEntry

		} // JobEntry

	} // RunEntry

	init {
		elem.h1("Jobs")
	}

	private val runEntries = HashMap<Int,RunEntry>()
	private val runsElem = elem.div(classes = setOf("runs"))
	private val periodElems = HashMap<TimePeriod,Disclosure>()
	private val emptyMessage = Div("no jobs to display", classes = setOf("empty"))

	fun init(msg: RealTimeS2C.ProjectStatus) {

		// clear current state
		runEntries.clear()
		runsElem.removeAll()
		periodElems.clear()

		val now = Date()

		// show job history, if any
		if (msg.recentRuns.isNotEmpty() || msg.hasOlderRuns) {

			// add a button to show any older runs
			val olderElem = TimePeriod.Older.elem.disclosedElem
			val loadOlderRunsButton = olderElem.button("Load older runs", classes = setOf("load-older-runs"))
			loadOlderRunsButton.onClick {
				AppScope.launch {

					// load the runs
					olderElem.remove(loadOlderRunsButton)
					val loading = olderElem.loading("Loading ...")
					val olderRuns = try {
						Services.projects.olderRuns(project.owner.id, project.projectId)
					} catch (t: Throwable) {
						olderElem.errorMessage(t)
						return@launch
					} finally {
						olderElem.remove(loading)
					}

					// display them
					if (olderRuns.isEmpty()) {
						Toast.info("No older runs to show")
					} else {
						addRuns(olderRuns, now)
					}
				}
			}

			addRuns(msg.recentRuns, now)

			// open the most recent run by default
			msg.recentRuns.lastOrNull()?.let { lastRun ->
				runEntries[lastRun.runId]?.elem?.open = true
			}

		} else {

			// no history, show a placeholder
			runsElem.add(emptyMessage)
		}
	}

	private fun addRuns(runs: List<ProjectRunData>, now: Date) {
		for (run in runs) {

			val entry = RunEntry(run.runId, Date(run.timestamp))
			runEntries[run.runId] = entry
			entry.init(run)

			// open the run if it's still runnung
			entry.elem.open = run.status in listOf(RunStatus.Waiting, RunStatus.Running)

			addRun(entry, now)
		}
	}

	fun initRun(msg: RealTimeS2C.ProjectRunInit) {

		// clear the empty message if needed
		if (runEntries.isEmpty()) {
			runsElem.removeAll()
		}

		val entry = RunEntry(msg.runId, Date(msg.timestamp))
		runEntries[msg.runId] = entry
		entry.init(msg)
		addRun(entry, Date())
	}

	private enum class TimePeriod(val display: String) {

		Future("The Future"),
		Today("Today"),
		Yesterday("Yesterday"),
		ThisWeek("This Week"),
		LastWeek("Last Week"),
		Older("Older");

		fun range(now: Date): IntRange =
			when (this) {
				Future -> unixInt(Moment(now).startOf("day").add(1, "day")) until Int.MAX_VALUE
				Today -> unixInt(Moment(now).startOf("day")) until unixInt(Moment(now).startOf("day").add(1, "day"))
				Yesterday -> unixInt(Moment(now).startOf("day").subtract(1, "days")) until unixInt(Moment(now).startOf("day"))
				ThisWeek -> unixInt(Moment(now).startOf("week")) until unixInt(Moment(now).startOf("week").add(1, "weeks"))
				LastWeek -> unixInt(Moment(now).startOf("week").subtract(1, "weeks")) until unixInt(Moment(now).startOf("week"))
				Older -> 0 until unixInt(Moment(now).startOf("week"))
			}

		companion object {

			private fun unixInt(thing: dynamic): Int =
				(thing.unix() as Number).toInt()

			fun from(now: Date, timestamp: Date): TimePeriod {
				val t = unixInt(Moment(timestamp))
				return values()
					.firstOrNull { t in it.range(now) }
					?: run {
						console.warn("timestamp somehow not in any range: ", timestamp, "now=", now)
						// this shouldn't be possible,
						// but just in case, return something semi-useful and don't crash
						Future
					}
			}
		}
	}

	private val TimePeriod.elem: Disclosure get() =
		periodElems.getOrPut(this) {
			runsElem.disclosure(label = { span(this@elem.display) })
				.apply {
					// show today's entries by default
					open = this@elem == TimePeriod.Today
				}
		}

	private fun addRun(entry: RunEntry, now: Date) {

		// get the time period for the run entry
		val period = TimePeriod.from(now, entry.timestamp)

		// build the run label
		// see: https://www.w3schools.com/jsref/jsref_tolocalestring.asp
		val timeStr = entry.timestamp.toLocaleTimeString(options = jsObject {
			asDynamic().timeStyle = "short"
		})
		entry.labelElem.content = "Run ${entry.runId} at " + when (period) {

			TimePeriod.Today,
			TimePeriod.Yesterday -> timeStr

			TimePeriod.ThisWeek -> entry.timestamp.toLocaleDateString(options = jsObject {
				weekday = "long"
			}) + ", " + timeStr

			else -> entry.timestamp.toLocaleDateString(options = jsObject {
				asDynamic().dateStyle = "medium"
			}) + ", " + timeStr
		}

		// add the run entry to the time period element
		period.elem.disclosedElem.add(entry.elem)
	}

	private fun removeRun(entry: RunEntry) {

		runEntries.remove(entry.runId)
			?: return

		// find the time period containing this run and remove it
		val period = periodElems.entries
			.find { (_, periodElem) -> entry.elem in periodElem.disclosedElem.getChildren() }
			?.key
			?: return
		val periodElem = period.elem
		periodElem.disclosedElem.remove(entry.elem)

		// if the time period is empty, remove that too
		if (periodElem.disclosedElem.getChildren().isEmpty()) {
			runsElem.remove(periodElem)
			periodElems.remove(period)
		}

		// if everything is empty, put the empty message back
		if (runsElem.getChildren().isEmpty()) {
			runsElem.add(emptyMessage)
		}
	}

	fun startRun(msg: RealTimeS2C.ProjectRunStart) {
		runEntries[msg.runId]?.start(msg)
	}

	fun startJob(msg: RealTimeS2C.JobStart) {
		runEntries[msg.runId]?.jobEntries?.get(msg.jobId)?.start(msg)
	}

	fun finishJob(msg: RealTimeS2C.JobFinish) {
		runEntries[msg.runId]?.jobEntries?.get(msg.jobId)?.finish(msg)
	}

	fun finishRun(msg: RealTimeS2C.ProjectRunFinish) {
		runEntries[msg.runId]?.finish(msg)
	}

	fun submitClusterJob(msg: RealTimeS2C.ClusterJobSubmit) {
		runEntries[msg.runId]?.jobEntries?.get(msg.jobId)?.submitClusterJob(msg)
	}

	fun startClusterJob(msg: RealTimeS2C.ClusterJobStart) {
		runEntries[msg.runId]?.jobEntries?.get(msg.jobId)?.startClusterJob(msg)
	}

	fun startClusterJobArray(msg: RealTimeS2C.ClusterJobArrayStart) {
		runEntries[msg.runId]?.jobEntries?.get(msg.jobId)?.startClusterJobArray(msg)
	}

	fun finishClusterJobArray(msg: RealTimeS2C.ClusterJobArrayEnd) {
		runEntries[msg.runId]?.jobEntries?.get(msg.jobId)?.finishClusterJobArray(msg)
	}

	fun finishClusterJob(msg: RealTimeS2C.ClusterJobEnd) {
		runEntries[msg.runId]?.jobEntries?.get(msg.jobId)?.finishClusterJob(msg)
	}

	fun jobDeleted(jobId: String) {

		val affectedRuns = runEntries.values.filter { it.jobDeleted(jobId) }

		// if the run is empty now, remove the run too
		for (run in affectedRuns) {
			if (run.jobEntries.isEmpty()) {
				removeRun(run)
			}
		}
	}

	fun highlightJobRun(runId: Int, jobId: String) {
		runEntries[runId]?.jobEntries?.get(jobId)?.highlight()
			?: Toast.error("No logs to highlight, job run is too old")
	}
}

fun RunStatus.toIcon() =
	when (this) {
		RunStatus.Waiting -> JobsMonitor.IconWaiting
		RunStatus.Running -> JobsMonitor.IconRunning
		RunStatus.Succeeded -> JobsMonitor.IconSucceeded
		RunStatus.Failed -> JobsMonitor.IconFailed
		RunStatus.Canceled -> JobsMonitor.IconCanceled
	}
