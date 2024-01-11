package edu.duke.bartesaghi.micromon.views.admin

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.errorMessage
import edu.duke.bartesaghi.micromon.flash
import edu.duke.bartesaghi.micromon.loading
import edu.duke.bartesaghi.micromon.services.Services
import edu.duke.bartesaghi.micromon.services.StandaloneData
import edu.duke.bartesaghi.micromon.services.unwrap
import io.kvision.core.Container
import io.kvision.html.*
import js.getHTMLElement


class AdminStandaloneTab(val elem: Container) {

	init {
		elem.addCssClass("tab")
		elem.addCssClass("admin-standalone")
		update()
	}

	private fun update() {

		elem.removeAll()

		AppScope.launch {

			// load the standalone data
			val loading = elem.loading("Loading ...")
			val data = try {
				Services.admin.standaloneData().unwrap()
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loading)
			}

			// show nothing if we're not in standalone mode
			if (data == null) {
				elem.p("Standalone mode is not running")
				return@launch
			}

			// show a refresh button
			elem.div {
				button("Refresh")
					.onClick {  update() }
			}

			// show the resources
			elem.h1("Resources")
			elem.indent {
				for (resource in data.resources) {
					h2(resource.name)
					indent {
						div("Available: ${resource.available}")
						div("Used: ${resource.used}")
						div("Total: ${resource.total}")
					}
				}
				h2("Available GPU IDs")
				indent {
					div(data.availableGpus.joinToString(", "))
				}
			}

			// running tasks
			elem.h1("Running Tasks")
			elem.indent {
				if (data.tasksRunning.isEmpty()) {
					div("(none)", classes = setOf("empty"))
				} else {
					for (task in data.tasksRunning) {
						div {
							link("${task.jobId} / ${task.taskId}")
								.onShowTask(task)
						}
					}
				}
			}

			// waiting tasks
			elem.h1("Waiting Tasks")
			elem.indent {
				if (data.tasksWaiting.isEmpty()) {
					div("(none)", classes = setOf("empty"))
				} else {
					for (task in data.tasksWaiting) {
						div {
							link("${task.jobId} / ${task.taskId}")
								.onShowTask(task)
						}
					}
				}
			}

			// show the jobs
			elem.h1("Jobs")
			elem.indent {
				if (data.jobs.isEmpty()) {
					div("(none)", classes = setOf("empty"))
				} else {
					for (job in data.jobs) {
						h2("${job.name} (${job.standaloneId})")
						indent {
							div("Cluster ID: ${job.clusterId}")
							div("Owner ID: ${job.ownerId}")
							div("Waiting Reason: ${job.waitingReason}")
							div("Canceled: ${job.canceled}")

							h3("Resources Requested:")
							indent {
								if (job.resources.isEmpty()) {
									div("(none)", classes = setOf("empty"))
								} else {
									for ((name, num) in job.resources) {
										div("$name: $num")
									}
								}
							}

							h3("Tasks:")
							for (task in job.tasks) {
								indent {
									id = job.ref(task).taskUid()

									h4("Task ${task.taskId}")
									indent {
										div("Array Element: ${task.arrayId ?: "(none)"}")
										div("Process ID: ${task.pid ?: "(none)"}")
										div("Waiting Reason: ${task.waitingReason ?: "(none)"}")
										div("Finished: ${task.finished}")

										h5("Resources Currently Reserved:")
										indent {
											if (task.resources.isEmpty()) {
												div("(none)", classes = setOf("empty"))
											} else {
												for ((resource, num) in task.resources) {
													div("$resource: $num")
												}
												div("GPU IDs: ${task.reservedGpus.joinToString(", ")}")
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}

	private fun Container.indent(block: Div.() -> Unit) =
		div(classes = setOf("indent"), init = block)

	private fun StandaloneData.TaskRef.taskUid(): String =
		"task-${jobId}-${taskId}"

	private fun Link.onShowTask(ref: StandaloneData.TaskRef) {
		onClick {
			elem.getHTMLElement()
				?.querySelector("#${ref.taskUid()}")
				?.flash()
		}
	}
}