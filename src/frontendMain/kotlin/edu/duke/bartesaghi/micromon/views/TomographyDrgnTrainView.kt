package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyDrgnTrainNode
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink
import io.kvision.utils.px


fun Widget.onGoToTomographyDrgnTrain(viewport: Viewport, project: ProjectData, job: TomographyDrgnTrainData) {
	onShow(TomographyDrgnTrainView.path(project, job)) {
		viewport.setView(TomographyDrgnTrainView(project, job))
	}
}


class TomographyDrgnTrainView(val project: ProjectData, val job: TomographyDrgnTrainData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyDrgnTrain/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyDrgnTrain.get(jobId)
						viewport.setView(TomographyDrgnTrainView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyDrgnTrainData) = "/project/${project.owner.id}/${project.projectId}/tomographyDrgnTrain/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyDrgnTrainData) {
			routing.show(path(project, job))
			viewport.setView(TomographyDrgnTrainView(project, job))
		}
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "tomography-drgn-train"))

	private var convergence: TomoDrgnConvergence? = null
	private var tabs: LazyTabPanel? = null
	private var convergenceTab: ConvergenceTab? = null
	private var fscTab: FscTab? = null
	private var ccMatrixTab: CcMatrixTab? = null
	private var reconstructionTab: ReconstructionTab? = null

	private val iterations = ArrayList<TomoDrgnConvergence.Iteration>()
	private val iterationsNav = BigListNav(
		iterations,
		initialIndex = null,
		initialLive = true,
		has100 = false
	).apply {
		// don't label the nav with the indices, use the iteration numbers themselves
		labeler = { i -> iterations[i].number.toString() }
	}

	private val currentIteration: TomoDrgnConvergence.Iteration? get() =
		iterationsNav.currentIndex
			?.let { iterations[it] }

	private var connector: WebsocketConnector? = null

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink(project.numberedName, icon = "fas fa-project-diagram")
					.onGoToProject(project)
				navLink(job.numberedName, icon = TomographyDrgnTrainNode.type.iconClass)
					.onGoToTomographyDrgnTrain(viewport, project, job)
			}
		}

		AppScope.launch {

			// load all the converenge, if any
			val loadingElem = elem.loading("Fetching convergence information ...")
			convergence = try {
				delayAtLeast(200) {
					Services.tomographyDrgnTrain.getConvergence(job.jobId)
						.unwrap()
				}
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loadingElem)
			}

			// make the tabs panel
			tabs = elem.lazyTabPanel {

				persistence = Storage::tomographyDrgnTrainTabIndex

				addTab("Encoder", "far fa-caret-square-right") { lazyTab ->
					convergenceTab = ConvergenceTab().also {
						lazyTab.elem.add(it)
						lazyTab.onActivate = { it.revalidate() }
					}
				}

				addTab("Decoder", "far fa-caret-square-left") { lazyTab ->
					fscTab = FscTab().also {
						lazyTab.elem.add(it)
						lazyTab.onActivate = { it.revalidate() }
					}
				}

				addTab("Volume correlation", "fas fa-ruler-combined") { lazyTab ->
					ccMatrixTab = CcMatrixTab().also {
						lazyTab.elem.add(it)
						lazyTab.onActivate = { it.revalidate() }
						it.reset()
					}
				}

				addTab("Reconstruction", "fas fa-question-circle") { lazyTab ->
					reconstructionTab = ReconstructionTab(iterationsNav.clone()).also {
						lazyTab.elem.add(it)
						lazyTab.onActivate = { it.revalidate() }
						it.reset()
					}
				}

				// TODO: 3D view tab
				// TODO: class movies tab
			}

			reset()

			// open the websocket connection to listen for server-side updates
			val connector = WebsocketConnector(RealTimeServices.tomographyDrgnTrain) { signaler, input, output ->

				// tell the server we want to listen to this session
				output.send(RealTimeC2S.ListenToTomographyDrgnTrain(job.jobId).toJson())

				signaler.connected()

				// wait for responses from the server
				for (msgstr in input) {
					when (val msg = RealTimeS2C.fromJson(msgstr)) {
						is RealTimeS2C.TomographyDrgnTrainConvergence -> update(msg.convergence)
						else -> Unit
					}
				}
			}
			this@TomographyDrgnTrainView.connector = connector
			elem.add(WebsocketControl(connector))
			connector.connect()
		}
	}

	override fun close() {
		connector?.disconnect()
	}

	private fun update(convergence: TomoDrgnConvergence) {

		// diff the convergences
		val old = this.convergence
		this.convergence = convergence

		// if we didn't even have an old one, reset
		if (old == null) {
			return reset()
		}

		// if the number of classes changed, reset
		if (old.parameters.numClasses != convergence.parameters.numClasses) {
			return reset()
		}

		// if we're missing any iterations, reset
		val newNumbers = convergence.iterationNumbers()
		val oldNumbers = old.iterationNumbers()
		if (oldNumbers.any { it !in newNumbers }) {
			return reset()
		}

		// show the new iterations
		convergence.iterations
			.filter { it.number !in oldNumbers }
			.let { addIterations(convergence, it) }
	}

	private fun reset() {

		iterations.clear()
		convergence?.let { iterations.addAll(it.iterations) }
		iterations.indices
			.lastOrNull()
			?.let { iterationsNav.showItem(it, false) }

		convergenceTab?.revalidate()
		fscTab?.revalidate()
		ccMatrixTab?.reset()
		reconstructionTab?.reset()
	}

	private fun addIterations(convergence: TomoDrgnConvergence, newIterations: List<TomoDrgnConvergence.Iteration>) {

		for (iter in newIterations) {
			iterations.add(iter)
			iterationsNav.newItem()
		}

		convergenceTab?.revalidate()
		fscTab?.revalidate()
		ccMatrixTab?.addIterations(convergence, newIterations)
	}


	private inner class ConvergenceTab : Div() {

		val plot0 = FetchImagePanel("Model loss", Storage::tomographyDrgnTrainPlot0Size, ImageSize.Medium) {
			ITomographyDrgnTrainService.plotPath(job.jobId, 0)
		}

		val plot1 = FetchImagePanel("PCA embedding", Storage::tomographyDrgnTrainPlot1Size, ImageSize.Medium) {
			ITomographyDrgnTrainService.plotPath(job.jobId, 1)
		}

		val plot2 = FetchImagePanel("UMAP embedding", Storage::tomographyDrgnTrainPlot2Size, ImageSize.Medium) {
			ITomographyDrgnTrainService.plotPath(job.jobId, 2)
		}

		val plot3 = FetchImagePanel("Embedding metrics", Storage::tomographyDrgnTrainPlot3Size, ImageSize.Medium) {
			ITomographyDrgnTrainService.plotPath(job.jobId, 3)
		}

		val numTiltsPlot = FetchImagePanel("Tilts per particle") {
			ITomographyDrgnTrainService.distributionPath(job.jobId)
		}

		init {
			// layout
			// NOTE: these plots are not displayed in numerical order
			add(plot1)
			add(plot2)
			add(plot3)
			add(plot0)
			add(numTiltsPlot)
		}

		fun revalidate() {
			plot0.img.revalidate()
			plot1.img.revalidate()
			plot2.img.revalidate()
			plot3.img.revalidate()
		}
	}


	private inner class FscTab : Div() {

		val plot7 = FetchImagePanel("FSC", Storage::tomographyDrgnTrainPlot7Size, ImageSize.Medium) {
			ITomographyDrgnTrainService.plotPath(job.jobId, 7)
		}

		val plot8 = FetchImagePanel("FSC at Nyquist", Storage::tomographyDrgnTrainPlot8Size, ImageSize.Medium) {
			ITomographyDrgnTrainService.plotPath(job.jobId, 8)
		}

		init {
			// layout
			add(plot7)
			add(plot8)
		}

		fun revalidate() {
			plot7.img.revalidate()
			plot8.img.revalidate()
		}
	}


	private inner class CcMatrixTab : Div() {

		val plots = ArrayList<Pair<TomoDrgnConvergence.Iteration,FetchImagePanel>>()

		fun reset() {

			plots.clear()
			removeAll()

			val convergence = convergence
				?: run {
					emptyMessage("Run not finished yet")
					return
				}

			val iters = convergence.iterations
				.takeIf { it.isNotEmpty() }
				?: run {
					emptyMessage("No iterations to show")
					return
				}

			addIterations(convergence, iters)
		}

		fun addIterations(convergence: TomoDrgnConvergence, newIterations: List<TomoDrgnConvergence.Iteration>) {
			for (iter in newIterations) {
				val epoch = convergence.epoch(iter)
				val panel = FetchImagePanel("Epoch $epoch", null, ImageSize.Medium) {
					ITomographyDrgnTrainService.pairwiseCCMatrixPath(job.jobId, epoch)
				}
				plots.add(iter to panel)
				add(panel)
			}
		}

		fun revalidate() {
			for ((_, panel) in plots) {
				panel.img.revalidate()
			}
		}
	}


	private inner class ReconstructionTab(
		iterationsNav: BigListNav
	): Div(classes = setOf("reconstruction-tab")) {

		private val classesRadio = ClassesRadio("Class")

		private val classesPanel = ContentSizedPanel(
			"Projections/Slices",
			ImageSize.values().map { it.approxWidth },
			(Storage.tomographyDrgnTrainClassSize ?: ImageSize.Small).ordinal
		)
		private val classesElem = Div(classes = setOf("classes"))
			.also { classesPanel.add(it) }

		private val plot4 = FetchImagePanel("Resolution", Storage::tomographyDrgnTrainPlot4Size, ImageSize.Medium) {
			ITomographyDrgnTrainService.plotPath(job.jobId, 4)
		}

		private val classImages = ArrayList<FetchImage>()
		private val classMrcDownloads = ArrayList<FileDownloadBadge>()


		init {

			val self = this // Kotlin DSLs are dumb ...

			// layout the tab
			div(classes = setOf("nav")) {
				add(iterationsNav)
				add(self.classesRadio)
			}
			add(classesPanel)
			add(plot4)

			// wire up events
			iterationsNav.onShow = {
				currentIteration?.let { showIteration(it) }
			}
			classesPanel.onResize = { index ->
				Storage.tomographyDrgnTrainClassSize = ImageSize.values()[index]
				currentIteration?.let { showIteration(it) }
			}
			classesRadio.onUpdate = {
				currentIteration?.let { showIteration(it) }
			}
		}

		fun reset() {

			classesRadio.count = 0

			fun abort(msg: String) {
				clearClasses()
				classesElem.emptyMessage(msg)
			}

			val convergence = convergence
				?: return abort("(No iterations to show)")

			val iter = currentIteration
				?: return abort("(No current iteration to show)")

			classesRadio.count = convergence.parameters.numClasses
			classesRadio.checkedClasses = classesRadio.allClasses()
			showIteration(iter)
		}

		fun clearClasses() {
			classesElem.removeAll()
			classImages.clear()
			classMrcDownloads.clear()
		}

		fun showIteration(iteration: TomoDrgnConvergence.Iteration) {

			val self = this // Kotlin DSLs are dumb ...

			clearClasses()

			if (classesRadio.checkedClasses.isEmpty()) {
				classesElem.emptyMessage("No classes to show")
				return
			}

			// show images for the classes
			val imageSize = ImageSize.values()[classesPanel.index]
			for (classNum in classesRadio.checkedClasses) {

				classesElem.div(classes = setOf("class")) {

					div(classes = setOf("frame")) {

						val img = fetchImage {
							width = imageSize.approxWidth.px
							fetch(ITomographyDrgnTrainService.classImagePath(job.jobId, iteration.number, classNum, imageSize))
						}
						self.classImages.add(img)

						div("Class $classNum", classes = setOf("label"))
					}

					val mrcDownload = FileDownloadBadge(
						filetype = ".mrc file",
						url = ITomographyDrgnTrainService.classMrcPath(job.jobId, iteration.number, classNum),
						filename = "reconstruction_${job.shortNumberedName}_iter_${iteration.number}_cls_${classNum}.mrc",
						loader = { Services.tomographyDrgnTrain.classMrcData(job.jobId, iteration.number, classNum) }
					)
					mrcDownload.load()
					self.classMrcDownloads.add(mrcDownload)
					add(mrcDownload)
				}
			}
		}

		fun revalidate() {

			for (img in classImages) {
				img.revalidate()
			}

			for (download in classMrcDownloads) {
				download.load()
			}

			plot4.img.revalidate()
		}
	}
}
