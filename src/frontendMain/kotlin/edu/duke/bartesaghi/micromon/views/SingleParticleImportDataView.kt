package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.SingleParticleImportDataNode
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.*
import io.kvision.html.*
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.navbar.navLink
import kotlinx.browser.document
import kotlinx.html.dom.create
import kotlinx.html.js.img
import kotlin.js.Date


fun Widget.onGoToSingleParticleImportData(viewport: Viewport, project: ProjectData, job: SingleParticleImportDataData) {
	onShow(SingleParticleImportDataView.path(project, job)) {
		viewport.setView(SingleParticleImportDataView(project, job))
	}
}

class SingleParticleImportDataView(val project: ProjectData, val job: SingleParticleImportDataData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/singleParticleImportData/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.singleParticleImportData.get(jobId)
						viewport.setView(SingleParticleImportDataView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: SingleParticleImportDataData) = "/project/${project.owner.id}/${project.projectId}/sp-import-${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: SingleParticleImportDataData) {
			routing.show(path(project, job))
			viewport.setView(SingleParticleImportDataView(project, job))
		}
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "single-particle-preprocessing"))

	private var micrographStatsElem = null as Div?

	private var tabs: LazyTabPanel? = null
	private var plots: PreprocessingPlots<MicrographMetadata>? = null
	private var filterTable: FilterTable<MicrographMetadata>? = null
	private var gallery: HyperGallery<MicrographMetadata>? = null
	private var liveTab: LiveTab? = null
	private var liveTabId: Int? = null
	private var statsLine: PypStatsLine? = null
	// NOTE: the same instance of these controls should be used for all the micrographs
	private val particleControls = MultiListParticleControls(project, job, ParticlesType.Particles2D, ParticlesList.autoParticles2D(job.jobId))

	private var connector: WebsocketConnector? = null

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink(project.numberedName, icon = "fas fa-project-diagram")
					.onGoToProject(project)
				navLink(job.numberedName, icon = SingleParticleImportDataNode.type.iconClass)
					.onGoToSingleParticleImportData(viewport, project, job)
			}
		}

		AppScope.launch {

			// load all the micrographs
			val loadingElem = elem.loading("Fetching micrographs ...")
			val (micrographs, pypStats, args) = try {
				delayAtLeast(200) {
					Triple(
						Services.jobs.getMicrographs(job.jobId)
							.sortedBy { it.timestamp }
							.toMutableList(),
						Services.jobs.pypStats(job.jobId),
						SingleParticleImportDataNode.pypArgs.get()
					)
				}
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loadingElem)
			}

			val finishedValues = job.args.finished?.values?.toArgValues(args)

			// show micrograph stats
			micrographStatsElem = elem.div("", classes = setOf("micrograph-stats"))
			updateMicrographStats(micrographs)

			// show PYP stats
			statsLine = PypStatsLine(pypStats)
				.also { elem.add(it) }

			// make the tabs panel
			tabs = elem.lazyTabPanel {

				persistence = Storage::singleParticleImportDataTabIndex

				addTab("Plots", "far fa-chart-bar") { lazyTab ->
					plots = PreprocessingPlots(
						micrographs,
						goto = { _, index ->
							showMicrograph(index, true)
						},
						tooltipImageUrl = { it.imageUrl(job, ImageSize.Small) }
					).apply {
						lazyTab.elem.add(this)
						load()
					}
				}

				addTab("Table", "fas fa-table") { lazyTab ->
					filterTable = FilterTable(
						"Micrograph",
						micrographs,
						MicrographProp.values().toList(),
						writable = project.canWrite(),
						showDetail = { elem, index, micrograph ->

							// show the micrograph stuff
							elem.div {
								link(micrograph.id, classes = setOf("link"))
									.onClick { showMicrograph(index, true) }
							}
							elem.add(MicrographImage(project, job, micrograph, particleControls, null).apply {
								loadParticles()
							})
							elem.add(Micrograph1DPlot(job, micrograph).apply {
								loadData()
							})
							elem.add(Micrograph2DPlot(job, micrograph))
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
					gallery = HyperGallery(micrographs, ImageSizes.from(ImageSize.Small)).apply {
						html = { micrograph ->
							listenToImageSize(document.create.img(src = micrograph.imageUrl(job, ImageSize.Small)))
						}
						linker = { _, index ->
							showMicrograph(index, true)
						}
						lazyTab.elem.add(this)
						update()
					}
				}

				liveTab = LiveTab(job, micrographs, finishedValues?.detectRad)
				liveTabId = addTab("Micrographs", "fas fa-desktop") {
					liveTab?.show(it.elem)
				}.id
			}

			// open the websocket connection to listen for server-side updates
			val connector = WebsocketConnector(RealTimeServices.singleParticlePreprocessing) { signaler, input, output ->

				// tell the server we want to listen to this session
				output.send(RealTimeC2S.ListenToSingleParticlePreprocessing(job.jobId).toJson())

				signaler.connected()

				// wait for responses from the server
				for (msgstr in input) {
					when (val msg = RealTimeS2C.fromJson(msgstr)) {
						is RealTimeS2C.UpdatedParameters -> {
							statsLine?.stats = msg.pypStats
						}
						is RealTimeS2C.UpdatedMicrograph -> updateMicrograph(msg.micrograph, micrographs)
						else -> Unit
					}
				}
			}
			this@SingleParticleImportDataView.connector = connector
			elem.add(WebsocketControl(connector))
			connector.connect()
		}
	}

	override fun close() {

		plots?.close()

		connector?.disconnect()
	}

	private fun updateMicrographStats(micrographs: List<MicrographMetadata>) {
		val numMicrographs = micrographs.size.formatWithDigitGroupsSeparator()
		val numParticles = particleControls.numParticles.formatWithDigitGroupsSeparator()
		micrographStatsElem?.content = "Total: $numMicrographs micrographs, $numParticles particles"
	}

	private fun showMicrograph(index: Int, stopLive: Boolean) {

		// make sure the live tab is showing
		liveTabId?.let {
			tabs?.showTab(it)
		}

		liveTab?.listNav?.showItem(index, stopLive)
	}

	private fun updateMicrograph(micrograph: MicrographMetadata, micrographs: MutableList<MicrographMetadata>) {

		// update the main list, if needed
		val index = micrographs.indexOfFirst { it.id == micrograph.id }
		if (index >= 0) {
			micrographs[index] = micrograph
		} else {
			micrographs.add(micrograph)
		}

		updateMicrographStats(micrographs)

		// update tabs
		plots?.update(micrograph)
		filterTable?.update()
		gallery?.update()
		liveTab?.listNav?.newItem()
	}

	// TODO: this shares a lot of code with the other proprocessing views... could it be refactored?
	private inner class LiveTab(
		val job: SingleParticleImportDataData,
		val micrographs: MutableList<MicrographMetadata>,
		val newParticleRadiusA: ValueA?
	) {

		private val micrographElem = Div()

		val listNav = BigListNav(micrographs, onSearch=micrographs::searchById) e@{ index ->

			// clear the previous contents
			micrographElem.removeAll()

			// get the indexed micrograph, if any
			val micrograph = micrographs.getOrNull(index)
			if (micrograph == null) {
				micrographElem.div("No micrographs to show", classes = setOf("empty"))
				return@e
			}

			// show metadata
			micrographElem.div(classes = setOf("stats")) {
				div {
					span("Name: ${micrograph.id}, processed on ${Date(micrograph.timestamp).toLocaleString()}")
					button("Show Log", classes = setOf("log-button")).onClick {
						LogView.showPopup("Log for Micrograph: ${micrograph.id}") {
							Services.jobs.getLog(job.jobId, micrograph.id)
						}
					}
				}
			}

			// show the particle picking controls
			micrographElem.add(particleControls)

			// show the micrograph image
			micrographElem.add(MicrographImage(
				project,
				job,
				micrograph,
				particleControls,
				newParticleRadiusA
			).apply {
				loadParticles()
				onParticlesChange = {
					updateMicrographStats(micrographs)
				}
			})

			// show the motion
			micrographElem.add(MicrographMotionPlot(job, micrograph).apply {
				loadData()
			})

			// show the 1D CTF profile
			micrographElem.add(Micrograph1DPlot(job, micrograph).apply {
				loadData()
			})

			// show the 2D CTF profile
			micrographElem.add(Micrograph2DPlot(job, micrograph))
		}

		fun show(elem: Container) {

			elem.addCssClass("live")

			// layout the tab
			elem.add(listNav)
			elem.add(micrographElem)

			// start with the newest micrograph
			listNav.showItem(micrographs.size - 1, false)
		}
	}
}
