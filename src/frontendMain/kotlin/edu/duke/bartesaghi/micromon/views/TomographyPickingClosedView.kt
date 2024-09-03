package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyPickingClosedNode
import edu.duke.bartesaghi.micromon.diagram.nodes.clientInfo
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.navbar.navLink
import kotlin.js.Date


fun Widget.onGoToTomographyPickingClosed(viewport: Viewport, project: ProjectData, job: TomographyPickingClosedData) {
	onShow(TomographyPickingClosedView.path(project, job)) {
		viewport.setView(TomographyPickingClosedView(project, job))
	}
}

class TomographyPickingClosedView(val project: ProjectData, val job: TomographyPickingClosedData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyPickingClosed/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyPickingClosed.get(jobId)
						viewport.setView(TomographyPickingClosedView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyPickingClosedData) = "/project/${project.owner.id}/${project.projectId}/tomographyPickingClosed/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyPickingClosedData) {
			routing.show(path(project, job))
			viewport.setView(TomographyPickingClosedView(project, job))
		}
	}

	override val elem = Div(classes = setOf("dock-page", "tomography-segmentation"))

	// NOTE: the same instance of these controls should be used for all the tilt series
	private val pickingControls = SingleListParticleControls(project, job)

	private var connector: WebsocketConnector? = null

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink(project.numberedName, icon = "fas fa-project-diagram")
					.onGoToProject(project)
				navLink(job.numberedName, icon = TomographyPickingClosedNode.type.iconClass)
					.onGoToTomographyPickingClosed(viewport, project, job)
			}
		}

		AppScope.launch {

			// load all the tilt series
			val loadingElem = elem.loading("Fetching tilt-series ...")
			val data = TiltSeriesesData()
			try {
				delayAtLeast(200) {
					data.loadForProject(job.jobId, job.clientInfo, job.args.finished?.values)
					when (val particles = data.particles) {
						null -> Unit
						is TiltSeriesesParticlesData.Data -> particles.list?.let { pickingControls.load(it) }
						else -> console.warn("Unexpected particles data: ${particles::class.simpleName}, skipping particles list")
					}
				}
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loadingElem)
			}

			// show tilt series stats
			val tiltSeriesStats = TiltSeriesStats()
			tiltSeriesStats.loadCounts(data, OwnerType.Project, job.jobId, pickingControls.list)
			elem.add(tiltSeriesStats)

			val tiltSeriesesElem = Div()
			val listNav = BigListNav(data.tiltSerieses, has100 = false) e@{ index ->

				// clear the previous contents
				tiltSeriesesElem.removeAll()

				// get the indexed tilt series, if any
				val tiltSeries = data.tiltSerieses.getOrNull(index)
				if (tiltSeries == null) {
					tiltSeriesesElem.div("No tilt series to show", classes = setOf("empty"))
					return@e
				}

				// show metadata
				tiltSeriesesElem.div(classes = setOf("stats")) {
					div {
						span("Name: ${tiltSeries.id}, processed on ${Date(tiltSeries.timestamp).toLocaleString()}")
						button("Show Log", classes = setOf("log-button")).onClick {
							LogView.showPopup("Log for Tilt Series: ${tiltSeries.id}") {
								Services.jobs.getLog(job.jobId, tiltSeries.id)
							}
						}
					}
				}

				// show the tilt series content in tabs
				tiltSeriesesElem.lazyTabPanel {

					persistence = Storage::tomographyPickingOpenTabIndex

					addTab("Reconstruction", "fas fa-desktop") { lazyTab ->

						val particlesImage = TomoParticlesImage.forProject(project, job, data, tiltSeries, pickingControls)
						particlesImage.onParticlesChange = {
							tiltSeriesStats.picked(data, pickingControls)
						}
						lazyTab.elem.add(particlesImage)

						lazyTab.elem.add(TomoSideViewImage(job.jobId, tiltSeries.id))

						AppScope.launch {
							particlesImage.load()
						}
					}

					// TODO: segmentation tab
				}
			}

			elem.add(listNav)
			elem.add(tiltSeriesesElem)

			// start with the newest tilt series
			listNav.showItem(data.tiltSerieses.size - 1, false)

			// open the websocket connection to listen for server-side updates
			val connector = WebsocketConnector(RealTimeServices.tomographyPickingClosed) { signaler, input, output ->

				// tell the server we want to listen to this session
				output.send(RealTimeC2S.ListenToTiltSerieses(job.jobId).toJson())

				signaler.connected()

				// wait for responses from the server
				for (msgstr in input) {
					when (val msg = RealTimeS2C.fromJson(msgstr)) {
						is RealTimeS2C.UpdatedParameters -> {
							listNav.reshow()
						}
						is RealTimeS2C.UpdatedTiltSeries -> {
							data.update(msg.tiltSeries)
							tiltSeriesStats.increment(data, msg.tiltSeries)
							listNav.newItem()
						}
						else -> Unit
					}
				}
			}
			this@TomographyPickingClosedView.connector = connector
			elem.add(WebsocketControl(connector))
			connector.connect()
		}
	}

	override fun close() {
		connector?.disconnect()
	}
}
