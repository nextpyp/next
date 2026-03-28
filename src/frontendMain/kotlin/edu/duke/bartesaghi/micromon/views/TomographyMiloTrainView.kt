package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyMiloTrainNode
import edu.duke.bartesaghi.micromon.pyp.TiltSeriesesData
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink


fun Widget.onGoToTomographyMiloTrain(viewport: Viewport, project: ProjectData, job: TomographyMiloTrainData) {
	onShow(TomographyMiloTrainView.path(project, job)) {
		viewport.setView(TomographyMiloTrainView(project, job))
	}
}

class TomographyMiloTrainView(val project: ProjectData, val job: TomographyMiloTrainData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyMiloTrain/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyMiloTrain.get(jobId)
						viewport.setView(TomographyMiloTrainView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyMiloTrainData) = "/project/${project.owner.id}/${project.projectId}/tomographyMiloTrain/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyMiloTrainData) {
			routing.show(path(project, job))
			viewport.setView(TomographyMiloTrainView(project, job))
		}
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "tomography-picking"))

	private val tiltSeriesStats = TiltSeriesStats()
	private var connector: WebsocketConnector? = null

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink(project.numberedName, icon = "fas fa-project-diagram")
					.onGoToProject(project)
				navLink(job.numberedName, icon = TomographyMiloTrainNode.type.iconClass)
					.onGoToTomographyMiloTrain(viewport, project, job)
			}
		}

		val bar = elem.div(classes = setOf("block-view-bar"))
		val content = elem.div(classes = setOf("block-view-content"))

		AppScope.launch {

			// load all the tilt series
			val loadingElem = content.loading("Fetching tilt-series ...")
			val data = TiltSeriesesData()
			val pypStats = try {
				delayAtLeast(200) {
					data.loadForProject(job, job.args.finished?.values)
					Services.jobs.pypStats(job.jobId)
				}
			} catch (t: Throwable) {
				content.errorMessage(t)
				return@launch
			} finally {
				content.remove(loadingElem)
			}

			// show tilt series stats
			tiltSeriesStats.set(data)
			bar.add(tiltSeriesStats)

			// show PYP stats
			val statsLine = PypStatsLine(pypStats)
				.also { bar.add(it) }

			// show the 2D results
			content.add(SizedPanel("Model loss", Storage.miloResults2dSize).apply {
				val img = image(ITomographyMiloTrainService.resultsPath(job.jobId), classes = setOf("full-width-image"))
				img?.refresh()
				// set the panel resize handler
				onResize = { newSize: ImageSize ->
					img.src = ITomographyMiloTrainService.resultsPath(job.jobId)
					Storage.miloResults2dSize = newSize
				}
			})

			// open the websocket connection to listen for server-side updates
			val connector = WebsocketConnector(RealTimeServices.tomographyPicking) { signaler, input, output ->

				// tell the server we want to listen to this job
				output.send(RealTimeC2S.ListenToTiltSerieses(job.jobId).toJson())

				signaler.connected()

				// wait for responses from the server
				for (msgstr in input) {
					when (val msg = RealTimeS2C.fromJson(msgstr)) {
						is RealTimeS2C.UpdatedParameters -> {
							statsLine.stats = msg.pypStats
						}
						is RealTimeS2C.UpdatedTiltSeries -> {
							data.update(msg.tiltSeries)
							tiltSeriesStats.increment(data, msg.tiltSeries)
						}
						else -> Unit
					}
				}
			}
			this@TomographyMiloTrainView.connector = connector
			bar.add(WebsocketControl(connector))
			bar.visible = true
			connector.connect()
		}
	}

	override fun close() {
		connector?.disconnect()
	}
}
