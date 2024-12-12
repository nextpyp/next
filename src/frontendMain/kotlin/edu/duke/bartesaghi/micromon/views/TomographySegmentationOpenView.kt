package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographySegmentationOpenNode
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import js.getHTMLElement
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.navbar.navLink
import kotlin.js.Date


fun Widget.onGoToTomographySegmentationOpen(viewport: Viewport, project: ProjectData, job: TomographySegmentationOpenData) {
	onShow(TomographySegmentationOpenView.path(project, job)) {
		viewport.setView(TomographySegmentationOpenView(project, job))
	}
}

class TomographySegmentationOpenView(val project: ProjectData, val job: TomographySegmentationOpenData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographySegmentationOpen/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographySegmentationOpen.get(jobId)
						viewport.setView(TomographySegmentationOpenView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographySegmentationOpenData) = "/project/${project.owner.id}/${project.projectId}/tomographySegmentationOpen/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographySegmentationOpenData) {
			routing.show(path(project, job))
			viewport.setView(TomographySegmentationOpenView(project, job))
		}
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "tomography-preprocessing"))

	private var tiltSeriesStats = TiltSeriesStats()
	private val tiltSeriesesElem = Div()

	private var statsLine: PypStatsLine? = null

	private var connector: WebsocketConnector? = null

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink(project.numberedName, icon = "fas fa-project-diagram")
					.onGoToProject(project)
				navLink(job.numberedName, icon = TomographySegmentationOpenNode.type.iconClass)
					.onGoToTomographySegmentationOpen(viewport, project, job)
			}
		}

		AppScope.launch {

			// load all the tilt series
			val loadingElem = elem.loading("Fetching tilt-series ...")
			val data = TiltSeriesesData()
			val pypStats = try {
				delayAtLeast(200) {
					data.loadForProject(job, job.args.finished?.values)
					Services.jobs.pypStats(job.jobId)
				}
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loadingElem)
			}

			val live = elem.div(classes = setOf("live"))

			// show tilt series stats
			tiltSeriesStats.loadCounts(data, OwnerType.Project, job.jobId, null)
			live.add(tiltSeriesStats)

			// show PYP stats
			statsLine = PypStatsLine(pypStats)
				.also { live.add(it) }

			val listNav = BigListNav(data.tiltSerieses, onSearch=data::searchById) e@{ index ->

				// clear the previous contents
				tiltSeriesesElem.removeAll()

				// some controls here only work when in the real DOM
				if (tiltSeriesesElem.getHTMLElement() == null) {
					return@e
				}

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

					persistence = Storage::tomographySegmentationOpenTabIndex

					addTab("Reconstruction", "fas fa-desktop") { lazyTab ->

						// show the download badge
						val recDownloadBadge = FileDownloadBadge(".rec file")
						lazyTab.elem.div(classes = setOf("spaced")) {
							add(recDownloadBadge)
						}

						// show the tomogram reconstruction
						val particlesImage = TomoParticlesImage.forProject(project, job, data, tiltSeries)
						lazyTab.elem.add(particlesImage)

						// show the side view
						lazyTab.elem.add(TomoSideViewImage(job.jobId, tiltSeries.id))

						AppScope.launch {

							recDownloadBadge.load {
								Services.jobs.recData(job.jobId, tiltSeries.id)
									.unwrap()
									?.let { recData ->
										FileDownloadBadge.Info(
											recData,
											"kv/jobs/${job.jobId}/data/${tiltSeries.id}/rec",
											"${job.jobId}_${tiltSeries.id}.rec"
										)
									}
							}

							particlesImage.load()
						}
					}

					// TODO: 3D tab
				}
			}
			live.add(listNav)
			live.add(tiltSeriesesElem)

			// start with the newest tilt series
			listNav.showItem(data.tiltSerieses.size - 1, false)

			// open the websocket connection to listen for server-side updates
			val connector = WebsocketConnector(RealTimeServices.tomographySegmentationOpen) { signaler, input, output ->

				// tell the server we want to listen to this session
				output.send(RealTimeC2S.ListenToTiltSerieses(job.jobId).toJson())

				signaler.connected()

				// wait for responses from the server
				for (msgstr in input) {
					when (val msg = RealTimeS2C.fromJson(msgstr)) {
						is RealTimeS2C.UpdatedParameters -> {
							statsLine?.stats = msg.pypStats
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
			this@TomographySegmentationOpenView.connector = connector
			elem.add(WebsocketControl(connector))
			connector.connect()
		}
	}

	override fun close() {
		connector?.disconnect()
	}
}
