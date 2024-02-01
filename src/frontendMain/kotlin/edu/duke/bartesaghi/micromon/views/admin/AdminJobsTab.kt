package edu.duke.bartesaghi.micromon.views.admin

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.LogView
import edu.duke.bartesaghi.micromon.errorMessage
import edu.duke.bartesaghi.micromon.loading
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Container
import io.kvision.html.*
import kotlin.js.Date


class AdminJobsTab(
	val elem: Container,
	val adminInfo: AdminInfo
) {

	init {
		elem.addCssClass("tab")
		elem.addCssClass("admin-jobs")
		update()
	}

	private fun update() {

		elem.removeAll()

		AppScope.launch {

			// load the jobs data
			val loading = elem.loading("Loading ...")
			val jobs = try {
				Services.admin.jobs()
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loading)
			}

			// show a refresh button
			elem.div {
				button("Refresh")
					.onClick {  update() }
			}

			// show the project runs
			elem.indent {
				if (jobs.projectRuns.isEmpty()) {
					div("(none)", classes = setOf("empty"))
				} else {
					for (projectRun in jobs.projectRuns) {
						showProjectRun(this, projectRun)
					}
				}
			}
		}
	}

	private fun showProjectRun(elem: Container, projectRun: ProjectRunAdminData) {

		val clusterJobs = projectRun.clusterJobs.associateBy { it.clusterJobId }

		elem.h2("Project Run")
		elem.indent {

			div("User Id: ${projectRun.userId}")
			div("User Name: ${projectRun.userName}")
			div("Project Id: ${projectRun.projectId}")
			div("Project Name: ${projectRun.projectName}")
			div("Run Id: ${projectRun.data.runId}")
			div("Started at: ${Date(projectRun.data.timestamp).toLocaleString()}")
			div("Status: ${projectRun.data.status}")

			if (projectRun.data.jobs.isEmpty()) {
				div("(no jobs)", classes = setOf("empty"))
			} else {
				for (jobRun in projectRun.data.jobs) {
					showJobRun(this, jobRun, clusterJobs)
				}
			}
		}
	}

	private fun showJobRun(elem: Container, jobRun: JobRunData, clusterJobs: Map<String,ClusterJobAdminData>) {

		elem.h3("Job")
		elem.indent {

			div("Job Id: ${jobRun.jobId}")
			div("Status: ${jobRun.status}")

			if (jobRun.clusterJobs.isEmpty()) {
				div("(no cluster jobs)", classes = setOf("empty"))
			} else {
				for (clusterJob in jobRun.clusterJobs) {
					showClusterJob(this, clusterJob, clusterJobs.getValue(clusterJob.clusterJobId))
				}
			}
		}
	}

	private fun showClusterJob(elem: Container, clusterJob: ClusterJobData, clusterJobAdmin: ClusterJobAdminData) {

		elem.h4("Cluster Job")
		elem.indent {

			div("Cluster Job Id: ${clusterJob.clusterJobId}")
			div("Web Name: ${clusterJob.webName}")
			div("Cluster Name: ${clusterJob.clusterName}")
			div("Status: ${clusterJob.status}")
			div("Array Info: ${clusterJob.arraySummary()}")
			div("${adminInfo.clusterMode} Id: ${clusterJobAdmin.clusterId ?: "(no id available, cluster job not launched)"}")
			div("Launch command:")
			add(clusterJobAdmin.launchResult?.command
				?.let { LogView.fromText(it) }
				?: Span("(none)")
			)
			div("Launch console output:")
			add(clusterJobAdmin.launchResult?.out
				?.let { LogView.fromText(it) }
				?: Span("(none)")
			)

			h5("History")
			indent {
				if (clusterJobAdmin.history.isEmpty()) {
					div("(no history)", classes = setOf("empty"))
				} else {
					for (historyEntry in clusterJobAdmin.history) {
						div("${historyEntry.status} at ${Date(historyEntry.timestamp).toLocaleString()}")
					}
				}
			}
		}
	}

	private fun ClusterJobData.arraySummary(): String {

		val arrayInfo = arrayInfo
			?: return "(none)"

		return "Started ${arrayInfo.started}, Finished ${arrayInfo.ended}, of Total: ${arrayInfo.size}"
	}

	private fun Container.indent(block: Div.() -> Unit) =
		div(classes = setOf("indent"), init = block)
}
