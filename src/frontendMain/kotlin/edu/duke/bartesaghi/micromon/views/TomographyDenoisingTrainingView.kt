package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyDenoisingTrainingNode
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.navbar.navLink
import kotlinx.browser.document
import kotlinx.html.dom.create
import kotlinx.html.js.img
import kotlin.js.Date


fun Widget.onGoToTomographyDenoisingTraining(viewport: Viewport, project: ProjectData, job: TomographyDenoisingTrainingData) {
	onShow(TomographyDenoisingTrainingView.path(project, job)) {
		viewport.setView(TomographyDenoisingTrainingView(project, job))
	}
}

class TomographyDenoisingTrainingView(val project: ProjectData, val job: TomographyDenoisingTrainingData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyDenoisingTraining/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyDenoisingTraining.get(jobId)
						viewport.setView(TomographyDenoisingTrainingView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyDenoisingTrainingData) = "/project/${project.owner.id}/${project.projectId}/tomographyDenoisingTraining/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyDenoisingTrainingData) {
			routing.show(path(project, job))
			viewport.setView(TomographyDenoisingTrainingView(project, job))
		}
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "tomography-picking"))

	private var tabs: LazyTabPanel? = null
	private var plots: PreprocessingPlots<TiltSeriesData>? = null
	private var filterTable: FilterTable<TiltSeriesData>? = null
	private var gallery: HyperGallery<TiltSeriesData>? = null
	private var liveTab: LiveTab? = null
	private var liveTabId: Int? = null
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
				navLink(job.numberedName, icon = TomographyDenoisingTrainingNode.type.iconClass)
					.onGoToTomographyDenoisingTraining(viewport, project, job)
			}
		}

		AppScope.launch {

			// load all the tilt series
			val loadingElem = elem.loading("Fetching tilt-series ...")
			val data = TiltSeriesesData()
			val pypStats = try {
				delayAtLeast(200) {
					data.loadForProject(job, job.args.finished?.values)
					when (val particles = data.particles) {
						null -> Unit
						is TiltSeriesesParticlesData.Data -> particles.list?.let { pickingControls.load(it) }
						else -> console.warn("Unexpected particles data: ${particles::class.simpleName}, skipping particles list")
					}
					Services.jobs.pypStats(job.jobId)
				}
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loadingElem)
			}

			val args = try {
				delayAtLeast(200) {
					TomographyDenoisingTrainingNode.pypArgs.get()
				}
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loadingElem)
			}
			val finishedValues = job.args.finished?.values?.toArgValues(args)

			// show tilt series stats
			val tiltSeriesStats = TiltSeriesStats()
			tiltSeriesStats.loadCounts(data, OwnerType.Project, job.jobId, pickingControls.list)
			elem.add(tiltSeriesStats)

			// show PYP stats
			val statsLine = PypStatsLine(pypStats)
				.also { elem.add(it) }

			// make the tabs panel
			tabs = elem.lazyTabPanel {

				persistence = Storage::tomographyDenoisingTrainingTabIndex

				addTab("Training Loss", "far fa-chart-bar") { lazyTab ->
					// show the results
					PlotsTab(lazyTab.elem)
				}

				addTab("Gallery", "fas fa-image") { lazyTab ->
					gallery = HyperGallery(data.tiltSerieses, ImageSizes.from(ImageSize.Small)).apply {
						html = { tiltSeries ->
							listenToImageSize(document.create.img(src = tiltSeries.imageUrl(job, ImageSize.Small)))
						}
						overlayLabel = { ts ->
							"${ts.numAutoParticles.formatWithDigitGroupsSeparator()} particle(s)"
						}
						linker = { _, index ->
							showTiltSeries(index, true)
						}
						lazyTab.elem.add(this)
						update()
					}
				}

				liveTab = LiveTab(data, tiltSeriesStats, finishedValues?.tomoDenoiseIsonet2Mask)
				liveTabId = addTab("Tomograms", "fas fa-tv") {
					liveTab?.show(it.elem)
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
							tiltSeriesStats.increment(data, msg.tiltSeries)

							// update tabs
							plots?.update(msg.tiltSeries)
							filterTable?.update()
							gallery?.update()
							liveTab?.listNav?.newItem()
						}
						else -> Unit
					}
				}
			}
			this@TomographyDenoisingTrainingView.connector = connector
			elem.add(WebsocketControl(connector))
			connector.connect()
		}
	}

	override fun close() {
		plots?.close()
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
		val fileDownload = FileDownloadBadge(
			filetype = ".tbz file",
			url = ITomographyMiloEvalService.dataPath(job.jobId),
			filename = "${job.jobId}_milo.tbz",
			loader = { Services.tomographyMiloEval.data(job.jobId) }
		)

		// show the file uplaod
		val fileUpload = FileUpload(
			ITomographyMiloEvalService.uploadPath(job.jobId),
			label = ".parquet",
			filename = "particles.parquet",
			accept = ".parquet"
		)

		init {
			elem.add(SizedPanel("Model loss", Storage.tomographyDenoisingTrainingResultsSize).apply {
				val img = image(ITomographyDenoisingTrainingService.trainResultsPath(job.jobId), classes = setOf("full-width-image"))
				img?.refresh()
				// set the panel resize handler
				onResize = { newSize: ImageSize ->
					img.src = ITomographyDenoisingTrainingService.trainResultsPath(job.jobId)
					Storage.tomographyDenoisingTrainingResultsSize = newSize
				}
			})
		}
	}

	private inner class LiveTab(
		val data: TiltSeriesesData,
		val stats: TiltSeriesStats,
		val tomoDenoiseIsonet2Mask: Boolean?,
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

			val tomoPanel = TomoDualPanel(project, job, data, tiltSeries, hideDownloadBadges = true, pickingControls = pickingControls, showIsonetOutputs = tomoDenoiseIsonet2Mask == true )
			tiltSeriesesElem.add(tomoPanel)
			AppScope.launch {
				tomoPanel.load()
				try {
					val updated = Services.jobs.getTiltSeries(job.jobId, tiltSeries.id)
					val idx = data.tiltSerieses.indexOfFirst { it.id == tiltSeries.id }
					if (idx >= 0) {
						data.tiltSerieses[idx] = updated
						gallery?.update()
					}
				} catch (_: Throwable) { }
			}
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
