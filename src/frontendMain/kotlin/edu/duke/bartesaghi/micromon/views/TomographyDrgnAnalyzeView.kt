package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyDrgnEvalNode
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink
import io.kvision.utils.px


fun Widget.onGoToTomographyDrgnEval(viewport: Viewport, project: ProjectData, job: TomographyDrgnEvalData) {
	onShow(TomographyDrgnEvalView.path(project, job)) {
		viewport.setView(TomographyDrgnEvalView(project, job))
	}
}


class TomographyDrgnEvalView(val project: ProjectData, val job: TomographyDrgnEvalData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyDrgnEval/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyDrgnEval.get(jobId)
						viewport.setView(TomographyDrgnEvalView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyDrgnEvalData) = "/project/${project.owner.id}/${project.projectId}/tomographyDrgnEval/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyDrgnEvalData) {
			routing.show(path(project, job))
			viewport.setView(TomographyDrgnEvalView(project, job))
		}
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "tomography-drgn-eval"))

	private var tabs: LazyTabPanel? = null
	private var reconstructionTab: ReconstructionTab? = null
	private var classesMovieTab: ClassesMovieTab? = null
	private var threeDeeTab: ThreeDeeTab? = null

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink(project.numberedName, icon = "fas fa-project-diagram")
					.onGoToProject(project)
				navLink(job.numberedName, icon = TomographyDrgnEvalNode.type.iconClass)
					.onGoToTomographyDrgnEval(viewport, project, job)
			}
		}

		AppScope.launch {

			// load all the converenge, if any
			val loadingElem = elem.loading("Fetching analysis ...")
			val evalParams: TomographyDrgnEvalParams? = try {
				delayAtLeast(200) {
					Services.tomographyDrgnEval.getParams(job.jobId)
						.unwrap()
				}
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loadingElem)
			}

			// set the mode handler
			val modeHandler: ModeHandler? = when (val mode = evalParams?.mode()) {
				null -> null
				is TomographyDrgnEvalMode.UMAP -> ModeHandler.UMAP(mode)
				is TomographyDrgnEvalMode.PCA -> ModeHandler.PCA(mode)
			}

			// make the tabs panel
			tabs = elem.lazyTabPanel {

				persistence = Storage::tomographyDrgnEvalTabIndex

				addTab("Reconstruction", "fas fa-desktop") { lazyTab ->
					reconstructionTab = ReconstructionTab(modeHandler).also {
						lazyTab.elem.add(it)
						lazyTab.onActivate = { it.revalidate() }
					}
				}

				addTab("Classes Movie", "fas fa-film") { lazyTab ->
					classesMovieTab = ClassesMovieTab(modeHandler).also {
						lazyTab.elem.add(it)
					}
				}

				addTab("3D View", "fas fa-cube") { lazyTab ->
					threeDeeTab = ThreeDeeTab(modeHandler).also {
						lazyTab.elem.add(it)
					}
				}
			}
		}
	}


	private sealed interface ModeHandler {

		class UMAP(
			val mode: TomographyDrgnEvalMode.UMAP
		) : ModeHandler

		class PCA(
			val mode: TomographyDrgnEvalMode.PCA
		) : ModeHandler {

			val dimensions: List<Int> =
				(1 .. mode.numDimensions)
					.toList()

			val dimensionsNav = BigListNav(
				dimensions,
				initialIndex = 0,
				initialLive = null, // no streaming, so hide the live options
				has100 = false
			)

			val dimensionsNavReconstruction = dimensionsNav.clone()
			val dimensionsNavClassesMovie = dimensionsNav.clone()

			val currentDimension: Int? get() =
				dimensionsNav.currentIndex
					?.let { dimensions[it] }
		}
	}


	private inner class ReconstructionTab(
		val modeHandler: ModeHandler?
	) : Div(classes = setOf("reconstruction-tab")) {

		private val classesRadio = ClassesRadio("Class")

		private val classesPanel = ContentSizedPanel(
			"Classes",
			ImageSize.values().map { it.approxWidth },
			(Storage.tomographyDrgnEvalClassSize ?: ImageSize.Small).ordinal
		)
		private val classesElem = Div(classes = setOf("classes"))
			.also { classesPanel.add(it) }

		private val classImages = ArrayList<FetchImage>()
		private val classMrcDownloads = ArrayList<FileDownloadBadge>()

		private val plotResolution = FetchImagePanel("K-means (sublots)", Storage::tomographyDrgnEvalPlotResolutionSize, ImageSize.Medium) {
			when (modeHandler) {
				is ModeHandler.UMAP -> ITomographyDrgnEvalService.plotResolutionPathUmap(job.jobId)
				is ModeHandler.PCA -> ITomographyDrgnEvalService.plotResolutionPathPca(job.jobId)
				else -> ""
			}
		}

		private val plotOccupancy = FetchImagePanel("K-means (labels)", Storage::tomographyDrgnEvalPlotOccupancySize, ImageSize.Medium) {
			when (modeHandler) {
				is ModeHandler.UMAP -> ITomographyDrgnEvalService.plotOccupancyPathUmap(job.jobId)
				is ModeHandler.PCA -> ITomographyDrgnEvalService.plotOccupancyPathPca(job.jobId)
				else -> ""
			}
		}


		init {

			val self = this // Kotlin DSLs are dumb ...

			// layout the tab
			div(classes = setOf("nav")) {
				when (self.modeHandler) {

					is ModeHandler.PCA -> {
						span("I-PC:")
						add(self.modeHandler.dimensionsNavReconstruction)
						self.modeHandler.dimensionsNavReconstruction.onShow = {
							self.updateClasses()
						}
					}

					else -> Unit
				}
				add(self.classesRadio)
			}
			div {
				add(self.plotResolution)
				add(self.plotOccupancy)
			}
			add(classesPanel)

			// init classes
			when (modeHandler) {

				is ModeHandler.UMAP -> {
					classesRadio.count = modeHandler.mode.numClasses
				}

				is ModeHandler.PCA -> {
					classesRadio.count = modeHandler.mode.numClasses
				}

				else -> Unit
			}
			classesRadio.checkedClasses = classesRadio.allClasses()

			// wire up events
			classesPanel.onResize = { index ->
				Storage.tomographyDrgnEvalClassSize = ImageSize.values()[index]
				updateClasses()
			}
			classesRadio.onUpdate = {
				updateClasses()
			}

			updateClasses()
		}

		fun updateClasses() {

			classesElem.removeAll()
			classImages.clear()
			classMrcDownloads.clear()

			if (modeHandler == null || classesRadio.checkedClasses.isEmpty()) {
				classesElem.emptyMessage("No classes to show")
				return
			}

			val self = this // Kotlin DSLs are dumb ...

			// show images for the classes
			val imageSize = ImageSize.values()[classesPanel.index]
			for (classNum in classesRadio.checkedClasses) {

				val (img, mrcDownload) = when (modeHandler) {

					is ModeHandler.UMAP -> {
						val img = FetchImage {
							width = imageSize.approxWidth.px
							fetch(ITomographyDrgnEvalService.classImagePathUmap(job.jobId, classNum, imageSize))
						}
						val mrcDownload = FileDownloadBadge(
							filetype = ".mrc file",
							url = ITomographyDrgnEvalService.classMrcPathUmap(job.jobId, classNum),
							filename = "reconstruction_${job.shortNumberedName}_cls_${classNum}.mrc",
							loader = { Services.tomographyDrgnEval.classMrcDataUmap(job.jobId, classNum) }
						)
						img to mrcDownload
					}

					is ModeHandler.PCA -> {
						val dim = modeHandler.currentDimension
							?: continue
						val img = FetchImage {
							width = imageSize.approxWidth.px
							fetch(ITomographyDrgnEvalService.classImagePathPca(job.jobId, dim, classNum, imageSize))
						}
						val mrcDownload = FileDownloadBadge(
							filetype = ".mrc file",
							url = ITomographyDrgnEvalService.classMrcPathPca(job.jobId, dim, classNum),
							filename = "reconstruction_${job.shortNumberedName}_dim_${dim}_cls_${classNum}.mrc",
							loader = { Services.tomographyDrgnEval.classMrcDataPca(job.jobId, dim, classNum) }
						)
						img to mrcDownload
					}
				}

				classesElem.div(classes = setOf("class")) {

					div(classes = setOf("frame")) {
						add(img)
						self.classImages.add(img)
						div("Class $classNum", classes = setOf("label"))
					}

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

			plotResolution.img.revalidate()
			plotOccupancy.img.revalidate()
		}
	}


	private inner class ClassesMovieTab(
		val modeHandler: ModeHandler?
	): Div() {

		private val movie = ClassesMovie(
			job,
			imagePather = { classNum, size ->
				when (modeHandler) {
					is ModeHandler.UMAP ->
						ITomographyDrgnEvalService.classImagePathUmap(job.jobId, classNum, size)
					is ModeHandler.PCA ->
						modeHandler.currentDimension?.let { dim ->
							ITomographyDrgnEvalService.classImagePathPca(job.jobId, dim, classNum, size)
						}
					else -> null
				} ?: ""
			}
		)

		init {

			// layout
			when (modeHandler) {
				is ModeHandler.PCA -> {
					add(modeHandler.dimensionsNavClassesMovie)
					modeHandler.dimensionsNavClassesMovie.onShow = {
						reset()
					}
				}
				else -> Unit
			}
			add(movie)

			reset()
		}

		fun reset() {
			val numClasses = when (modeHandler) {
				is ModeHandler.UMAP -> modeHandler.mode.numClasses
				is ModeHandler.PCA -> modeHandler.mode.numClasses
				else -> null
			}
			movie.update(numClasses)
		}

		// TODO: revalidate?
	}


	private inner class ThreeDeeTab(
		val modeHandler: ModeHandler?
	) : Div() {

		private val viewer = ThreeDeeViewer()

		init {
			// layout
			add(viewer)

			// init the volumes
			viewer.volumes = when (modeHandler) {

				is ModeHandler.UMAP ->
					(1 .. modeHandler.mode.numClasses).map { classNum ->
						ThreeDeeViewer.VolumeData(
							name = "Class $classNum",
							url = ITomographyDrgnEvalService.classMrcPathUmap(job.jobId, classNum)
						)
					}

				is ModeHandler.PCA ->
					(1 .. modeHandler.mode.numDimensions).flatMap { dim ->
						(1 .. modeHandler.mode.numClasses).map { classNum ->
							ThreeDeeViewer.VolumeData(
								name = "Dimension $dim, Class $classNum",
								url = ITomographyDrgnEvalService.classMrcPathPca(job.jobId, dim, classNum)
							)
						}
					}

				else -> emptyList()
			}
		}
	}
}
