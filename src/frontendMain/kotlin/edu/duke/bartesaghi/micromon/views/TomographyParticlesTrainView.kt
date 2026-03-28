package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyParticlesTrainNode
import edu.duke.bartesaghi.micromon.pyp.TiltSeriesesData
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink
import kotlinx.browser.document
import kotlinx.html.dom.create
import kotlinx.html.js.img
import kotlin.js.Date


fun Widget.onGoToTomographyParticlesTrain(viewport: Viewport, project: ProjectData, job: TomographyParticlesTrainData) {
	onShow(TomographyParticlesTrainView.path(project, job)) {
		viewport.setView(TomographyParticlesTrainView(project, job))
	}
}

class TomographyParticlesTrainView(val project: ProjectData, val job: TomographyParticlesTrainData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyParticlesTrain/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyParticlesTrain.get(jobId)
						viewport.setView(TomographyParticlesTrainView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyParticlesTrainData) = "/project/${project.owner.id}/${project.projectId}/tomographyParticlesTrain/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyParticlesTrainData) {
			routing.show(path(project, job))
			viewport.setView(TomographyParticlesTrainView(project, job))
		}
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "tomography-picking"))

	private var tabs: LazyTabPanel? = null
	private var gallery: HyperGallery<TiltSeriesData>? = null
	private var liveTab: LiveTab? = null
	private var liveTabId: Int? = null
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
				navLink(job.numberedName, icon = TomographyParticlesTrainNode.type.iconClass)
					.onGoToTomographyParticlesTrain(viewport, project, job)
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
			
			val args = try {
				delayAtLeast(200) {
					TomographyParticlesTrainNode.pypArgs.get()
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

			tabs = content.lazyTabPanel {

				persistence = Storage::tomographyParticlesTrainTabIndex

				addTab("Training", "far fa-chart-bar") { lazyTab ->
					PlotsTab(lazyTab.elem)
				}

				addTab("Gallery", "fas fa-image") { lazyTab ->
					HyperGallery(data.tiltSerieses, ImageSizes.from(ImageSize.Small)).apply {
						html = { tiltSeries ->
							listenToImageSize(document.create.img(src = tiltSeries.imageUrl(job, ImageSize.Small)))
						}
						linker = { _, index ->
							showTiltSeries(index, true)
						}
						lazyTab.elem.add(this)
						update()
					}
				}

				liveTab = LiveTab(data,showDenoised=finishedValues?.detectNn3dUseDenoised)
				liveTabId = addTab("Tomograms", "fas fa-desktop") {
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
							gallery?.update()
							liveTab?.listNav?.newItem()
						}
						else -> Unit
					}
				}
			}
			this@TomographyParticlesTrainView.connector = connector
			bar.add(WebsocketControl(connector))
			bar.visible = true
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

		init {
			elem.add(SizedPanel("Model Loss", Storage.particlesResults2dSize).apply {
				val img = image(ITomographyParticlesTrainService.resultsPath(job.jobId), classes = setOf("full-width-image"))

				// set the panel resize handler
				onResize = { newSize: ImageSize ->
					img.src = ITomographyParticlesTrainService.resultsPath(job.jobId)
					Storage.particlesResults2dSize = newSize
				}
			})
		}

	}

	private inner class LiveTab(
		val data: TiltSeriesesData,
		val showDenoised: Boolean?
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
				}
			}

			val tomoPanel = TomoDualPanel(project, job, data, tiltSeries, showDenoised = showDenoised == true, showHeatMap = true, hideDownloadBadges = true, pickingControls = null)
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
