package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyMiloEvalNode
import edu.duke.bartesaghi.micromon.pyp.TiltSeriesesData
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink
import kotlinx.browser.document
import kotlinx.html.dom.create
import kotlinx.html.js.img
import kotlin.js.Date


fun Widget.onGoToTomographyMiloEval(viewport: Viewport, project: ProjectData, job: TomographyMiloEvalData) {
	onShow(TomographyMiloEvalView.path(project, job)) {
		viewport.setView(TomographyMiloEvalView(project, job))
	}
}

class TomographyMiloEvalView(val project: ProjectData, val job: TomographyMiloEvalData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyMiloEval/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyMiloEval.get(jobId)
						viewport.setView(TomographyMiloEvalView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyMiloEvalData) = "/project/${project.owner.id}/${project.projectId}/tomographyMiloEval/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyMiloEvalData) {
			routing.show(path(project, job))
			viewport.setView(TomographyMiloEvalView(project, job))
		}
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "tomography-milo-eval"))

	private var tabs: LazyTabPanel? = null
	private var liveTab: LiveTab? = null
	private var liveTabId: Int? = null

	private var connector: WebsocketConnector? = null


	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink(project.numberedName, icon = "fas fa-project-diagram")
					.onGoToProject(project)
				navLink(job.numberedName, icon = TomographyMiloEvalNode.type.iconClass)
					.onGoToTomographyMiloEval(viewport, project, job)
			}
		}

		elem.h1("MiloPYP Pattern Mining")

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

			// show PYP stats
			val statsLine = PypStatsLine(pypStats)
				.also { elem.add(it) }

			tabs = elem.lazyTabPanel {

				persistence = Storage::tomographyMiloEvalTabIndex

				addTab("Plots", "far fa-chart-bar") { lazyTab ->
					PlotsTab(lazyTab.elem)
				}

				addTab("Gallery", "fas fa-image") { lazyTab ->
					HyperGallery(data.tiltSerieses, ImageSizes.from(ImageSize.Small)).apply {
						html = { tiltSeries ->
							val url = ITomographyMiloEvalService.results3dTiltSeriesPath(job.jobId, tiltSeries.id, ImageSize.Small)
							listenToImageSize(document.create.img(src = url))
						}
						linker = { _, index ->
							showTiltSeries(index, true)
						}
						lazyTab.elem.add(this)
						update()
					}
				}

				liveTab = LiveTab(data)
				liveTabId = addTab("Tilt-Series", "fas fa-desktop") { lazyTab ->
					liveTab?.show(lazyTab.elem)
				}.id
			}

			// open the websocket connection to listen for server-side updates
			val connector = WebsocketConnector(RealTimeServices.tomographyPicking) { signaler, input, output ->

				// tell the server we want to listen to this session
				output.send(RealTimeC2S.ListenToTiltSerieses(job.jobId).toJson())

				signaler.connected()

				// wait for responses from the server
				for (msgstr in input) {
					when (val msg = RealTimeS2C.fromJson(msgstr)) {
						is RealTimeS2C.UpdatedParameters -> {
							liveTab?.listNav?.reshow()
							statsLine.stats = msg.pypStats
						}
						is RealTimeS2C.UpdatedTiltSeries -> {
							data.update(msg.tiltSeries)

							// update tabs
							liveTab?.listNav?.newItem()
						}
						else -> Unit
					}
				}
			}
			this@TomographyMiloEvalView.connector = connector
			elem.add(WebsocketControl(connector))
			connector.connect()
		}
	}

	override fun close() {
		connector?.disconnect()
	}

	private fun showTiltSeries(index: Int, stopLive: Boolean) {

		// make sure the live tab is showing
		liveTabId?.let {
			tabs?.showTab(it)
		}

		liveTab?.listNav?.showItem(index, stopLive)
	}


	private inner class PlotsTab(
		elem: Container
	) {

		// show the file download
		val fileDownload = FileDownloadBadge(".gzip file")

		// show the file uplaod
		val fileUpload = FileUpload(
			ITomographyMiloEvalService.uploadPath(job.jobId),
			label = ".parquet",
			filename = "particles.parquet",
			accept = ".parquet"
		)

		init {

			AppScope.launch {
				Services.tomographyMiloEval.data(job.jobId)
					.unwrap()
					?.let {
						fileDownload.show(FileDownloadBadge.Info(
							it,
							ITomographyMiloEvalService.dataPath(job.jobId),
							"${job.jobId}_milo.gzip"
						))
					}
			}

			elem.div(classes = setOf("files")) {
				span("Download:")
				add(fileDownload)
				span("Upload:")
				add(fileUpload)
			}

			elem.add(SizedPanel("Class Labels", Storage.miloResults2dSize).apply {
				val img = image(ITomographyMiloEvalService.results2dLabelsPath(job.jobId, size), classes = setOf("full-width-image"))

				// set the panel resize handler
				onResize = { newSize: ImageSize ->
					img.src = ITomographyMiloEvalService.results2dLabelsPath(job.jobId, size)
					Storage.miloResults2dSize = newSize
				}
			})

			elem.add(SizedPanel("UMAP Embedding", Storage.miloResults2dSize).apply {
				val img = image(ITomographyMiloEvalService.results2dPath(job.jobId, size), classes = setOf("full-width-image"))

				// set the panel resize handler
				onResize = { newSize: ImageSize ->
					img.src = ITomographyMiloEvalService.results2dPath(job.jobId, size)
					Storage.miloResults2dSize = newSize
				}
			})
		}
	}


	private inner class LiveTab(
		val data: TiltSeriesesData
	) {

		private val tiltSeriesesElem = Div()

		val listNav = BigListNav(data.tiltSerieses, onSearch=data::searchById) e@{ index ->

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

			tiltSeriesesElem.add(SizedPanel("Tomogram visualization", Storage.miloResults3dTiltSeriesSize).apply {
				val img = image(ITomographyMiloEvalService.results3dTiltSeriesPath(job.jobId, tiltSeries.id, size), classes = setOf("full-width-image"))

				// set the panel resize handler
				onResize = { newSize: ImageSize ->
					img.src = ITomographyMiloEvalService.results3dTiltSeriesPath(job.jobId, tiltSeries.id, size)
					Storage.miloResults3dTiltSeriesSize = newSize
				}
			})
		}

		fun show(elem: Container) {

			elem.addCssClass("live")

			// layout the tab
			elem.add(listNav)
			elem.add(tiltSeriesesElem)

			// start with the newest tilt series
			listNav.showItem(data.tiltSerieses.size - 1, false)
		}
	}
}
