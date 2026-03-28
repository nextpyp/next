package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.formatWithDigitGroupsSeparator
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyParticlesEvalNode
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


fun Widget.onGoToTomographyParticlesEval(viewport: Viewport, project: ProjectData, job: TomographyParticlesEvalData) {
	onShow(TomographyParticlesEvalView.path(project, job)) {
		viewport.setView(TomographyParticlesEvalView(project, job))
	}
}

class TomographyParticlesEvalView(val project: ProjectData, val job: TomographyParticlesEvalData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyParticlesEval/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyParticlesEval.get(jobId)
						viewport.setView(TomographyParticlesEvalView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyParticlesEvalData) = "/project/${project.owner.id}/${project.projectId}/tomographyParticlesEval/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyParticlesEvalData) {
			routing.show(path(project, job))
			viewport.setView(TomographyParticlesEvalView(project, job))
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
				navLink(job.numberedName, icon = TomographyParticlesEvalNode.type.iconClass)
					.onGoToTomographyParticlesEval(viewport, project, job)
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
					when (val particles = data.particles) {
						null -> Unit
						is TiltSeriesesParticlesData.Data -> particles.list?.let { pickingControls.load(it) }
						else -> console.warn("Unexpected particles data: ${particles::class.simpleName}, skipping particles list")
					}
					Services.jobs.pypStats(job.jobId)
				}
			} catch (t: Throwable) {
				content.errorMessage(t)
				return@launch
			} finally {
				content.remove(loadingElem)
			}

			val args = try {
				delayAtLeast(200) {
					TomographyParticlesEvalNode.pypArgs.get()
				}
			} catch (t: Throwable) {
				content.errorMessage(t)
				return@launch
			}
			val finishedValues = job.args.finished?.values?.toArgValues(args)

			// show tilt series stats
			val tiltSeriesStats = TiltSeriesStats()
			tiltSeriesStats.loadCounts(data, OwnerType.Project, job.jobId, pickingControls.list)
			bar.add(tiltSeriesStats)

			// show PYP stats
			val statsLine = PypStatsLine(pypStats)
				.also { bar.add(it) }

			// make the tabs panel
			tabs = content.lazyTabPanel {

				persistence = Storage::tomographyParticlesEvalTabIndex

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
						TiltSeriesProp.tableColumns,
						TiltSeriesProp.tableColumns,
						writable = project.canWrite(),
						showDetail = { elem, index, tiltSeries ->

							// show the tilt series stuff
							elem.div {
								link(tiltSeries.id, classes = setOf("link"))
									.onClick { showTiltSeries(index, true) }
							}
							elem.add(TiltSeriesImage(project, job, tiltSeries).apply {
								loadParticles()
							})
						}
					).apply {
						lazyTab.elem.add(this)
						load()
					}
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

				liveTab = LiveTab(data, tiltSeriesStats,showDenoised=finishedValues?.detectNn3dUseDenoised)
				liveTabId = addTab("Particles", "fas fa-crosshairs") {
					liveTab?.show(it.elem)
				}.id
			}

			// open the websocket connection to listen for server-side updates
			val connector = WebsocketConnector(RealTimeServices.tomographyParticlesEval) { signaler, input, output ->

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
			this@TomographyParticlesEvalView.connector = connector
			bar.add(WebsocketControl(connector))
			bar.visible = true
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


	private inner class LiveTab(
		val data: TiltSeriesesData,
		val stats: TiltSeriesStats,
		val showDenoised: Boolean?
	) {

		val tiltSeriesesElem = Div()
		private val denoisedAvailabilityByTiltSeriesId = HashMap<String, Boolean>()
		private var lastRequestedIndex: Int? = null

		val listNav = BigListNav(data.tiltSerieses, onSearch=data::searchById) e@{ index ->

			// clear the previous contents
			tiltSeriesesElem.removeAll()

			// get the indexed tilt series, if any
			val tiltSeries = data.tiltSerieses.getOrNull(index)
			if (tiltSeries == null) {
				tiltSeriesesElem.div("No tilt series to show", classes = setOf("empty"))
				return@e
			}
			tiltSeriesesElem.loading("Checking tilt-series data ...")
			lastRequestedIndex = index

			AppScope.launch {

				val showDenoisedTab = if (showDenoised == true) {
					denoisedAvailabilityByTiltSeriesId.getOrPut(tiltSeries.id) {
						try {
							Services.jobs.denData(job.jobId, tiltSeries.id).isNotEmpty()
						} catch (t: Throwable) {
							console.warn("Failed to query denoised data availability for tilt series ${tiltSeries.id}", t)
							false
						}
					}
				} else {
					false
				}

				// Ignore stale requests if the user has already selected another tilt series.
				if (lastRequestedIndex != index) {
					return@launch
				}

				tiltSeriesesElem.removeAll()

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

				val tomoPanel = TomoDualPanel(
					project,
					job,
					data,
					tiltSeries,
					pickingControls = pickingControls,
					showDenoised = showDenoisedTab,
					hideSideViews = false,
					hideDownloadBadges = false,
					hideOriginalTabWhenOnlyTab = true
				)
				tiltSeriesesElem.add(tomoPanel)
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
