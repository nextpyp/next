package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyPreprocessingNode
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import js.getHTMLElement
import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.navbar.navLink
import kotlinx.html.img
import kotlin.js.Date


fun Widget.onGoToTomographyPreprocessing(viewport: Viewport, project: ProjectData, job: TomographyPreprocessingData) {
	onShow(TomographyPreprocessingView.path(project, job)) {
		viewport.setView(TomographyPreprocessingView(project, job))
	}
}

class TomographyPreprocessingView(val project: ProjectData, val job: TomographyPreprocessingData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyPreprocessing/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyPreprocessing.get(jobId)
						viewport.setView(TomographyPreprocessingView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyPreprocessingData) = "/project/${project.owner.id}/${project.projectId}/tomographyPreprocessing/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyPreprocessingData) {
			routing.show(path(project, job))
			viewport.setView(TomographyPreprocessingView(project, job))
		}
	}

	override val elem = Div(classes = setOf("dock-page", "tomography-preprocessing"))

	private var tiltSeriesStats = TiltSeriesStats()

	private var tabs: LazyTabPanel? = null
	private var plots: PreprocessingPlots<TiltSeriesData>? = null
	private var filterTable: FilterTable<TiltSeriesData>? = null
	private var gallery: HyperGallery<TiltSeriesData>? = null
	private var liveTab: LiveTab? = null
	private var liveTabId: Int? = null
	private var statsLine: PypStatsLine? = null
	// NOTE: the same instance of these controls should be used for all the tilt series
	private val pickingControls = ProjectParticleControls(project, job)

	private var connector: WebsocketConnector? = null

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink(project.numberedName, icon = "fas fa-project-diagram")
					.onGoToProject(project)
				navLink(job.numberedName, icon = TomographyPreprocessingNode.type.iconClass)
					.onGoToTomographyPreprocessing(viewport, project, job)
			}
		}

		AppScope.launch {

			// load all the tilt series
			val loadingElem = elem.loading("Fetching tilt-series ...")
			val data = TiltSeriesesData()
			val pypStats = try {
				delayAtLeast(200) {
					data.load(job)
					if (data.virusMode != null) {
						pickingControls.newParticlesType = ParticlesType.Virions3D
						pickingControls.setList(ParticlesList.autoVirions(job.jobId))
					} else {
						pickingControls.newParticlesType = ParticlesType.Particles3D
						pickingControls.setList(ParticlesList.autoParticles3D(job.jobId))
					}
					Services.jobs.pypStats(job.jobId)
				}
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loadingElem)
			}

			// show tilt series stats
			elem.add(tiltSeriesStats)
			tiltSeriesStats.update(data, pickingControls)

			// show PYP stats
			statsLine = PypStatsLine(pypStats)
				.also { elem.add(it) }

			// make the tabs panel
			tabs = elem.lazyTabPanel {

				persistence = Storage::tomographyPreprocessingTabIndex

				addTab("Plots", "far fa-chart-bar") { lazyTab ->
					plots = PreprocessingPlots(
						data.tiltSerieses,
						goto = { _, index ->
							showTiltSeries(index, true)
						},
						tooltipImageUrl = { it.imageUrl(job, ImageSize.Small) },
					).apply {
						lazyTab.elem.add(this)
						load()
					}
				}

				addTab("Table", "fas fa-table") { lazyTab ->
					filterTable = FilterTable(
						"Tilt Series",
						data.tiltSerieses,
						TiltSeriesProp.values().toList(),
						writable = project.canWrite(),
						showDetail = { elem, index, tiltSeries ->

							// show the tilt series stuff
							elem.div {
								link(tiltSeries.id, classes = setOf("link"))
									.onClick { showTiltSeries(index, true) }
							}
							elem.add(TiltSeriesImage(project, job, tiltSeries, data.imagesScale).apply {
								loadParticles()
							})
							elem.add(TiltSeries1DPlot(job, tiltSeries).apply {
								loadData()
							})
							elem.add(TiltSeries2DPlot(job, tiltSeries))
						},
						filterer = FilterTable.Filterer(
							save = { filter -> Services.blocks.saveFilter(job.jobId, filter) },
							delete = { name -> Services.blocks.deleteFilter(job.jobId, name) },
							names = { Services.blocks.listFilters(job.jobId) },
							get = { name -> Services.blocks.getFilter(job.jobId, name) }
						)
					).apply {
						lazyTab.elem.add(this)
						load()
					}
				}

				addTab("Gallery", "fas fa-image") { lazyTab ->
					gallery = HyperGallery(
						data.tiltSerieses,
						html = { img(src = it.imageUrl(job, ImageSize.Small)) },
						linker = { _, index ->
							showTiltSeries(index, true)
						}
					).apply {
						lazyTab.elem.add(this)
						data.tiltSerieses.firstOrNull()?.let { tiltSeries ->
							loadIfNeeded { fetchImageSizes(tiltSeries.imageUrl(job, ImageSize.Small)) }
						}
					}
				}

				liveTab = LiveTab(job, data)
				liveTabId = addTab("Tilt Series", "fas fa-desktop") {
					liveTab?.show(it.elem)
				}.id
			}

			// open the websocket connection to listen for server-side updates
			val connector = WebsocketConnector(RealTimeServices.tomographyPreprocessing) { signaler, input, output ->

				// tell the server we want to listen to this session
				output.send(RealTimeC2S.ListenToTomographyPreprocessing(job.jobId).toJson())

				signaler.connected()

				// wait for responses from the server
				for (msgstr in input) {
					when (val msg = RealTimeS2C.fromJson(msgstr)) {
						is RealTimeS2C.UpdatedParameters -> {
							statsLine?.stats = msg.pypStats
							data.imagesScale = msg.imagesScale
							liveTab?.listNav?.reshow()
						}
						is RealTimeS2C.UpdatedTiltSeries -> updateTiltSeries(msg, data)
						else -> Unit
					}
				}
			}
			this@TomographyPreprocessingView.connector = connector
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

	private fun updateTiltSeries(msg: RealTimeS2C.UpdatedTiltSeries, data: TiltSeriesesData) {

		data.update(msg)

		tiltSeriesStats.update(data, pickingControls)

		// update tabs
		plots?.update(msg.tiltSeries)
		filterTable?.update()
		gallery?.loadIfNeeded { fetchImageSizes(msg.tiltSeries.imageUrl(job, ImageSize.Small)) }
		liveTab?.listNav?.newItem()
	}

	private inner class LiveTab(
		val job: TomographyPreprocessingData,
		val data: TiltSeriesesData
	) {

		private val tiltSeriesesElem = Div()

		val listNav = BigListNav(data.tiltSerieses, has100 = false) e@{ index ->

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

			val tomoPanel = TomoMultiPanel(project, job, data, tiltSeries, pickingControls)
			tomoPanel.particlesImage.onParticlesChange = {
				tiltSeriesStats.update(data, pickingControls)
			}
			tiltSeriesesElem.add(tomoPanel)
			AppScope.launch {
				tomoPanel.load()
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
