package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyDrgnEvalVolsNode
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink
import io.kvision.utils.px


fun Widget.onGoToTomographyDrgnEvalVols(viewport: Viewport, project: ProjectData, job: TomographyDrgnEvalVolsData) {
	onShow(TomographyDrgnEvalVolsView.path(project, job)) {
		viewport.setView(TomographyDrgnEvalVolsView(project, job))
	}
}


class TomographyDrgnEvalVolsView(val project: ProjectData, val job: TomographyDrgnEvalVolsData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyDrgnEvalVols/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyDrgnEvalVols.get(jobId)
						viewport.setView(TomographyDrgnEvalVolsView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyDrgnEvalVolsData) = "/project/${project.owner.id}/${project.projectId}/tomographyDrgnEvalVols/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyDrgnEvalVolsData) {
			routing.show(path(project, job))
			viewport.setView(TomographyDrgnEvalVolsView(project, job))
		}
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "tomography-drgn-eval"))

	private var tabs: LazyTabPanel? = null
	private var umapReconstructionTab: UmapReconstructionTab? = null
	private var umapClassesMovieTab: UmapClassesMovieTab? = null
	private var umapThreeDeeTab: UmapThreeDeeTab? = null
	private var pcaReconstructionTab: PcaReconstructionTab? = null
	private var pcaClassesMovieTab: PcaClassesMovieTab? = null
	private var pcaThreeDeeTab: PcaThreeDeeTab? = null

	// init the dimension nav
	private val dimensions = ArrayList<Int>()
	private val dimensionsNav = BigListNav(
		dimensions,
		initialIndex = 0,
		initialLive = null, // no streaming, so hide the live options
		has100 = false
	)
	private fun currentDimension(): Int? =
		dimensionsNav.currentIndex
			?.let { dimensions[it] }


	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink(project.numberedName, icon = "fas fa-project-diagram")
					.onGoToProject(project)
				navLink(job.numberedName, icon = TomographyDrgnEvalVolsNode.type.iconClass)
					.onGoToTomographyDrgnEvalVols(viewport, project, job)
			}
		}

		AppScope.launch {

			// load all the converenge, if any
			val loadingElem = elem.loading("Fetching analysis ...")
			val evalParams: TomographyDrgnEvalVolsParams? = try {
				delayAtLeast(200) {
					Services.tomographyDrgnEvalVols.getParams(job.jobId)
						.unwrap()
				}
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loadingElem)
			}

			if (evalParams == null) {
				elem.emptyMessage("No data to show yet")
				return@launch
			}

			// init the dimensions
			dimensions.clear()
			dimensions.addAll(
				(1 .. evalParams.numDimensions)
					.toList()
			)
			dimensionsNav.newItem()

			// make the tabs panel
			tabs = elem.lazyTabPanel {

				persistence = Storage::tomographyDrgnEvalVolsTabIndex

				if (!evalParams.skipumap) {

					addTab("K-means (classes)", "fas fa-chart-pie") { lazyTab ->
						umapReconstructionTab = UmapReconstructionTab(evalParams.numClasses).also {
							lazyTab.elem.add(it)
							lazyTab.onActivate = { it.revalidate() }
						}
					}

					addTab("K-means (movie)", "fas fa-film") { lazyTab ->
						umapClassesMovieTab = UmapClassesMovieTab(evalParams.numClasses).also {
							lazyTab.elem.add(it)
						}
					}

					addTab("K-means (3D view)", "fas fa-cube") { lazyTab ->
						umapThreeDeeTab = UmapThreeDeeTab(evalParams.numClasses).also {
							lazyTab.elem.add(it)
						}
					}
				}

				addTab("PCA (classes)", "fas fa-ruler-horizontal") { lazyTab ->
					pcaReconstructionTab = PcaReconstructionTab(10).also {
						lazyTab.elem.add(it)
						lazyTab.onActivate = { it.revalidate() }
					}
				}

				addTab("PCA (movie)", "fas fa-film") { lazyTab ->
					pcaClassesMovieTab = PcaClassesMovieTab(10).also {
						lazyTab.elem.add(it)
					}
				}

				addTab("PCA (3D view)", "fas fa-cube") { lazyTab ->
					pcaThreeDeeTab = PcaThreeDeeTab(10).also {
						lazyTab.elem.add(it)
					}
				}
			}
		}
	}

	override fun close() {
		umapThreeDeeTab?.close()
		pcaThreeDeeTab?.close()
	}


	private inner class UmapReconstructionTab(
		numClasses: Int
	) : Div(classes = setOf("reconstruction-tab")) {

		private val classesRadio = ClassesRadio("Class", true)
		private val classesPanel = ContentSizedPanel(
			"Classes",
			ImageSize.values().map { it.approxWidth },
			(Storage.tomographyDrgnEvalVolsClassSize ?: ImageSize.Small).ordinal
		)
		private val classesElem = Div(classes = setOf("classes"))
			.also { classesPanel.add(it) }

		private val plotImages = ArrayList<FetchImage>()
		private val classImages = ArrayList<FetchImage>()
		private val classMrcDownloads = ArrayList<FileDownloadBadge>()

		init {

			val self = this // Kotlin DSLs are dumb ...

			// layout the tab
			div(classes = setOf("nav")) {
				add(self.classesRadio)
			}
			add(classesPanel)
			div {

				fun addPlot(plot: FetchImagePanel) {
					self.plotImages.add(plot.img)
					add(plot)
				}

				addPlot(FetchImagePanel("K-means (sublots)", Storage::tomographyDrgnEvalVolsPlotUmapScatterSubplotkmeanslabelSize, ImageSize.Medium) {
					ITomographyDrgnEvalVolsService.plotUmapScatterSubplotkmeanslabel(job.jobId)
				})
				addPlot(FetchImagePanel("K-means (labels)", Storage::tomographyDrgnEvalVolsPlotUmapScatterColorkmeanslabelSize, ImageSize.Medium) {
					ITomographyDrgnEvalVolsService.plotUmapScatterColorkmeanslabel(job.jobId)
				})
				/* 				
				addPlot(FetchImagePanel("TODO", Storage::tomographyDrgnEvalVolsPlotUmapScatterAnnotatekmeansSize, ImageSize.Medium) {
					ITomographyDrgnEvalVolsService.plotUmapScatterAnnotatekmeans(job.jobId)
				})
				*/				
				addPlot(FetchImagePanel("K-means (hexbin)", Storage::tomographyDrgnEvalVolsPlotUmapHexbinAnnotatekmeansSize, ImageSize.Medium) {
					ITomographyDrgnEvalVolsService.plotUmapHexbinAnnotatekmeans(job.jobId)
				})
				addPlot(FetchImagePanel("PCA (subplots)", Storage::tomographyDrgnEvalVolsPlotPcaScatterSubplotkmeanslabelSize, ImageSize.Medium) {
					ITomographyDrgnEvalVolsService.plotPcaScatterSubplotkmeanslabel(job.jobId)
				})
				addPlot(FetchImagePanel("PCA (labels)", Storage::tomographyDrgnEvalVolsPlotPcaScatterClorkmeanslabelSize, ImageSize.Medium) {
					ITomographyDrgnEvalVolsService.plotPcaScatterClorkmeanslabel(job.jobId)
				})
				/*
				addPlot(FetchImagePanel("TODO", Storage::tomographyDrgnEvalVolsPlotPcaScatterColorkmeanslabelSize, ImageSize.Medium) {
					ITomographyDrgnEvalVolsService.plotPcaScatterColorkmeanslabel(job.jobId)
				})
				addPlot(FetchImagePanel("TODO", Storage::tomographyDrgnEvalVolsPlotPcaScatterAnnotatekmeansSize, ImageSize.Medium) {
					ITomographyDrgnEvalVolsService.plotPcaScatterAnnotatekmeans(job.jobId)
				})
				 */
				addPlot(FetchImagePanel("PCA (hexbin)", Storage::tomographyDrgnEvalVolsPlotPcaHexbinAnnotatekmeansSize, ImageSize.Medium) {
					ITomographyDrgnEvalVolsService.plotPcaHexbinAnnotatekmeans(job.jobId)
				})
				addPlot(FetchImagePanel("Class distribution per tomogram", Storage::tomographyDrgnEvalVolsPlotTomogramLabelDistributionSize, ImageSize.Medium) {
					ITomographyDrgnEvalVolsService.plotTomogramLabelDistribution(job.jobId)
				})
			}

			// init classes
			classesRadio.count = numClasses
			classesRadio.checkedClasses = classesRadio.allClasses()

			// wire up events
			classesPanel.onResize = { index ->
				Storage.tomographyDrgnEvalVolsClassSize = ImageSize.values()[index]
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

			if (classesRadio.checkedClasses.isEmpty()) {
				classesElem.emptyMessage("No classes to show")
				return
			}

			val self = this // Kotlin DSLs are dumb ...

			// show images for the classes
			val imageSize = ImageSize.values()[classesPanel.index]
			for (classNum in classesRadio.checkedClasses) {
				classesElem.div(classes = setOf("class")) {

					div(classes = setOf("frame")) {
						val img = FetchImage {
							width = imageSize.approxWidth.px
							fetch(ITomographyDrgnEvalVolsService.classImagePathUmap(job.jobId, classNum, imageSize))
						}
						add(img)
						self.classImages.add(img)
						div("Class $classNum", classes = setOf("label"))
					}

					val mrcDownload = FileDownloadBadge(
						filetype = ".mrc file",
						url = ITomographyDrgnEvalVolsService.classMrcPathUmap(job.jobId, classNum),
						filename = "reconstruction_${job.shortNumberedName}_cls_${classNum}.mrc",
						loader = { Services.tomographyDrgnEvalVols.classMrcDataUmap(job.jobId, classNum) }
					)
					add(mrcDownload)
					self.classMrcDownloads.add(mrcDownload)
					mrcDownload.load()
				}
			}
		}

		fun revalidate() {
			for (img in plotImages) {
				img.revalidate()
			}
			for (img in classImages) {
				img.revalidate()
			}
			for (download in classMrcDownloads) {
				download.load()
			}
		}
	}


	private inner class UmapClassesMovieTab(
		val numClasses: Int
	): Div() {

		private val movie = ClassesMovie(
			job,
			imagePather = { classNum, size ->
				ITomographyDrgnEvalVolsService.classImagePathUmap(job.jobId, classNum, size)
			}
		)

		init {

			// layout
			add(movie)

			reset()
		}

		fun reset() {
			movie.update(numClasses)
		}

		// TODO: revalidate?
	}


	private inner class UmapThreeDeeTab(
		numClasses: Int
	) : Div() {

		private val viewer = ThreeDeeViewer()

		init {

			// layout
			add(viewer)

			// init the volumes
			viewer.volumes =
				(1 .. numClasses).map { classNum ->
					ThreeDeeViewer.VolumeData(
						name = "Class $classNum",
						url = ITomographyDrgnEvalVolsService.classMrcPathUmap(job.jobId, classNum)
					)
				}
		}

		fun close() {
			viewer.close()
		}
	}


	private inner class PcaReconstructionTab(
		numClasses: Int
	) : Div(classes = setOf("reconstruction-tab")) {

		private val classesRadio = ClassesRadio("Class", true)
		private val classesPanel = ContentSizedPanel(
			"Classes",
			ImageSize.values().map { it.approxWidth },
			(Storage.tomographyDrgnEvalVolsClassSize ?: ImageSize.Small).ordinal
		)
		private val classesElem = Div(classes = setOf("classes"))
			.also { classesPanel.add(it) }

		private val dimensionsNav = this@TomographyDrgnEvalVolsView.dimensionsNav.clone()

		private val plotUmapColorlatentpcaSize =
			FetchImagePanel("UMAP (colored PC)", Storage::tomographyDrgnEvalVolsPlotUmapColorlatentpcaSize, ImageSize.Medium) {
				"/images/placeholder.svgz"
			}

		private val plotImages = ArrayList<FetchImage>()
		private val classImages = ArrayList<FetchImage>()
		private val classMrcDownloads = ArrayList<FileDownloadBadge>()

		init {

			val self = this // Kotlin DSLs are dumb ...

			// layout the tab
			div(classes = setOf("nav")) {
				span("PC:")
				add(self.dimensionsNav)
				add(self.classesRadio)
			}
			add(classesPanel)
			div {

				fun addPlot(plot: FetchImagePanel) {
					self.plotImages.add(plot.img)
					add(plot)
				}

				addPlot(FetchImagePanel("UMAP (labels)", Storage::tomographyDrgnEvalVolsPlotUmapScatterAnnotatepcaSize, ImageSize.Medium) {
					ITomographyDrgnEvalVolsService.plotUmapScatterAnnotatepca(job.jobId)
				})
				addPlot(FetchImagePanel("UMAP (hexbin)", Storage::tomographyDrgnEvalVolsPlotUmapHexbinAnnotatepcaSize, ImageSize.Medium) {
					ITomographyDrgnEvalVolsService.plotUmapHexbinAnnotatepca(job.jobId)
				})
				addPlot(FetchImagePanel("PCA (labels)", Storage::tomographyDrgnEvalVolsPlotPcaScatterAnnotatepcaSize, ImageSize.Medium) {
					ITomographyDrgnEvalVolsService.plotPcaScatterAnnotatepca(job.jobId)
				})
				addPlot(FetchImagePanel("PCA (hexbin)", Storage::tomographyDrgnEvalVolsPlotPcaHexbinAnnotatepcaSize, ImageSize.Medium) {
					ITomographyDrgnEvalVolsService.plotPcaHexbinAnnotatepca(job.jobId)
				})

				addPlot(self.plotUmapColorlatentpcaSize)

			}

			// init classes
			classesRadio.count = numClasses
			classesRadio.checkedClasses = classesRadio.allClasses()

			// wire up events
			classesPanel.onResize = { index ->
				Storage.tomographyDrgnEvalVolsClassSize = ImageSize.values()[index]
				updateClasses()
			}
			classesRadio.onUpdate = {
				updateClasses()
			}
			dimensionsNav.onShow = {
				updateClasses()
			}

			updateClasses()
		}

		fun updateClasses() {

			classesElem.removeAll()
			classImages.clear()
			classMrcDownloads.clear()

			val dim = currentDimension()
				?: run {
					classesElem.emptyMessage("No dimension chosen")
					return
				}
			if (classesRadio.checkedClasses.isEmpty()) {
				classesElem.emptyMessage("No classes to show")
				return
			}

			val self = this // Kotlin DSLs are dumb ...

			// update the dimension-dependent plots
			plotUmapColorlatentpcaSize.pather = {
				ITomographyDrgnEvalVolsService.plotUmapColorlatentpca(job.jobId, dim)
			}
			plotUmapColorlatentpcaSize.fetch()

			// show images for the classes
			val imageSize = ImageSize.values()[classesPanel.index]
			for (classNum in classesRadio.checkedClasses) {

				classesElem.div(classes = setOf("class")) {

					div(classes = setOf("frame")) {
						val img = FetchImage {
							width = imageSize.approxWidth.px
							fetch(ITomographyDrgnEvalVolsService.classImagePathPca(job.jobId, dim, classNum, imageSize))
						}
						add(img)
						self.classImages.add(img)
						div("Class $classNum", classes = setOf("label"))
					}

					val mrcDownload = FileDownloadBadge(
						filetype = ".mrc file",
						url = ITomographyDrgnEvalVolsService.classMrcPathPca(job.jobId, dim, classNum),
						filename = "reconstruction_${job.shortNumberedName}_dim_${dim}_cls_${classNum}.mrc",
						loader = { Services.tomographyDrgnEvalVols.classMrcDataPca(job.jobId, dim, classNum) }
					)
					add(mrcDownload)
					self.classMrcDownloads.add(mrcDownload)
					mrcDownload.load()
				}
			}
		}

		fun revalidate() {
			for (img in plotImages) {
				img.revalidate()
			}
			for (img in classImages) {
				img.revalidate()
			}
			for (download in classMrcDownloads) {
				download.load()
			}
		}
	}


	private inner class PcaClassesMovieTab(
		val numClasses: Int
	): Div() {

		private val dimensionsNav = this@TomographyDrgnEvalVolsView.dimensionsNav.clone()

		private val movie = ClassesMovie(
			job,
			imagePather = { classNum, size ->
				currentDimension()?.let { dim ->
					ITomographyDrgnEvalVolsService.classImagePathPca(job.jobId, dim, classNum, size)
				}
				?: ""
			}
		)

		init {

			// layout
			add(dimensionsNav)
			dimensionsNav.onShow = {
				reset()
			}
			add(movie)

			reset()
		}

		fun reset() {
			movie.update(numClasses)
		}

		// TODO: revalidate?
	}


	private inner class PcaThreeDeeTab(
		numClasses: Int
	) : Div() {

		private val viewer = ThreeDeeViewer()

		init {
			// layout
			add(viewer)

			// init the volumes
			viewer.volumes =
				(1 .. dimensions.size).flatMap { dim ->
					(1 .. numClasses).map { classNum ->
						ThreeDeeViewer.VolumeData(
							name = "Dimension $dim, Class $classNum",
							url = ITomographyDrgnEvalVolsService.classMrcPathPca(job.jobId, dim, classNum)
						)
					}
				}
		}

		fun close() {
			viewer.close()
		}
	}
}
