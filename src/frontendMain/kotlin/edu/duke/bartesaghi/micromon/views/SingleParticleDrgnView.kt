package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.SingleParticleDrgnNode
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink
import io.kvision.utils.px


fun Widget.onGoToSingleParticleDrgn(viewport: Viewport, project: ProjectData, job: SingleParticleDrgnData) {
	onShow(SingleParticleDrgnView.path(project, job)) {
		viewport.setView(SingleParticleDrgnView(project, job))
	}
}


class SingleParticleDrgnView(val project: ProjectData, val job: SingleParticleDrgnData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/singleParticleDrgn/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.singleParticleDrgn.get(jobId)
						viewport.setView(SingleParticleDrgnView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: SingleParticleDrgnData) = "/project/${project.owner.id}/${project.projectId}/singleParticleDrgn/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: SingleParticleDrgnData) {
			routing.show(path(project, job))
			viewport.setView(SingleParticleDrgnView(project, job))
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
				navLink(job.numberedName, icon = SingleParticleDrgnNode.type.iconClass)
					.onGoToSingleParticleDrgn(viewport, project, job)
			}
		}

		AppScope.launch {

			// load all the converenge, if any
			val loadingElem = elem.loading("Fetching analysis ...")
			val evalParams: SingleParticleDrgnParams? = try {
				delayAtLeast(200) {
					Services.singleParticleDrgn.getParams(job.jobId)
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

				persistence = Storage::singleParticleDrgnTabIndex

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

				addTab("PC sampling (classes)", "fas fa-ruler-horizontal") { lazyTab ->
					pcaReconstructionTab = PcaReconstructionTab(10).also {
						lazyTab.elem.add(it)
						lazyTab.onActivate = { it.revalidate() }
					}
				}

				addTab("PC sampling (movie)", "fas fa-film") { lazyTab ->
					pcaClassesMovieTab = PcaClassesMovieTab(10).also {
						lazyTab.elem.add(it)
					}
				}

				addTab("PC sampling (3D view)", "fas fa-cube") { lazyTab ->
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
			(Storage.singleParticleDrgnClassSize ?: ImageSize.Small).ordinal
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

				addPlot(FetchImagePanel("UMAP", Storage::singleParticleDrgnPlotUmapSize, ImageSize.Medium) {
					ISingleParticleDrgnService.plotUmap(job.jobId)
				})
				addPlot(FetchImagePanel("UMAP (hexbin)", Storage::singleParticleDrgnPlotUmapHexbinSize, ImageSize.Medium) {
					ISingleParticleDrgnService.plotUmapHexbin(job.jobId)
				})
				/*
				addPlot(FetchImagePanel("PCA", Storage::singleParticleDrgnPlotPcaSize, ImageSize.Medium) {
					ISingleParticleDrgnService.plotPca(job.jobId)
				})
				 */
				addPlot(FetchImagePanel("PCA (hexbin)", Storage::singleParticleDrgnPlotPcaHexbinSize, ImageSize.Medium) {
					ISingleParticleDrgnService.plotPcaHexbin(job.jobId)
				})
				/* 				
				addPlot(FetchImagePanel("TODO", Storage::singleParticleDrgnPlotUmapScatterAnnotatekmeansSize, ImageSize.Medium) {
					ISingleParticleDrgnService.plotUmapScatterAnnotatekmeans(job.jobId)
				})
				addPlot(FetchImagePanel("TODO", Storage::singleParticleDrgnPlotPcaScatterSubplotkmeanslabelSize, ImageSize.Medium) {
					ISingleParticleDrgnService.plotPcaScatterSubplotkmeanslabel(job.jobId)
				})
				addPlot(FetchImagePanel("TODO", Storage::singleParticleDrgnPlotPcaScatterColorkmeanslabelSize, ImageSize.Medium) {
					ISingleParticleDrgnService.plotPcaScatterColorkmeanslabel(job.jobId)
				})
				addPlot(FetchImagePanel("TODO", Storage::singleParticleDrgnPlotPcaScatterAnnotatekmeansSize, ImageSize.Medium) {
					ISingleParticleDrgnService.plotPcaScatterAnnotatekmeans(job.jobId)
				})
				addPlot(FetchImagePanel("Class distribution per tomogram", Storage::singleParticleDrgnPlotTomogramLabelDistributionSize, ImageSize.Medium) {
					ISingleParticleDrgnService.plotTomogramLabelDistribution(job.jobId)
				})
				*/
			}

			// init classes
			classesRadio.count = numClasses
			classesRadio.checkedClasses = classesRadio.allClasses()

			// wire up events
			classesPanel.onResize = { index ->
				Storage.singleParticleDrgnClassSize = ImageSize.values()[index]
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
							fetch(ISingleParticleDrgnService.classImagePathUmap(job.jobId, classNum, imageSize))
						}
						add(img)
						self.classImages.add(img)
						div("Class $classNum", classes = setOf("label"))
					}

					val mrcDownload = FileDownloadBadge(
						filetype = ".mrc file",
						url = ISingleParticleDrgnService.classMrcPathUmap(job.jobId, classNum),
						filename = "reconstruction_${job.shortNumberedName}_cls_${classNum}.mrc",
						loader = { Services.singleParticleDrgn.classMrcDataUmap(job.jobId, classNum) }
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
				ISingleParticleDrgnService.classImagePathUmap(job.jobId, classNum, size)
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
						url = ISingleParticleDrgnService.classMrcPathUmap(job.jobId, classNum)
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
			(Storage.singleParticleDrgnClassSize ?: ImageSize.Small).ordinal
		)
		private val classesElem = Div(classes = setOf("classes"))
			.also { classesPanel.add(it) }

		private val dimensionsNav = this@SingleParticleDrgnView.dimensionsNav.clone()

		private val plotUmapColorlatentpcaSize =
			FetchImagePanel("UMAP (colored PC)", Storage::singleParticleDrgnPlotUmapColorlatentpcaSize, ImageSize.Medium) {
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

				addPlot(FetchImagePanel("UMAP", Storage::singleParticleDrgnPlotUmapTraversalSize, ImageSize.Medium) {
					ISingleParticleDrgnService.plotUmapTraversal(job.jobId)
				})
				addPlot(FetchImagePanel("UMAP (connected)", Storage::singleParticleDrgnPlotUmapTraversalConnectedSize, ImageSize.Medium) {
					ISingleParticleDrgnService.plotUmapTraversalConnected(job.jobId)
				})
				addPlot(FetchImagePanel("PCA (hexbin)", Storage::singleParticleDrgnPlotPcaHexbinTraversalSize, ImageSize.Medium) {
					ISingleParticleDrgnService.plotPcaHexbinTraversal(job.jobId)
				})

				// addPlot(self.plotUmapColorlatentpcaSize)

			}

			// init classes
			classesRadio.count = numClasses
			classesRadio.checkedClasses = classesRadio.allClasses()

			// wire up events
			classesPanel.onResize = { index ->
				Storage.singleParticleDrgnClassSize = ImageSize.values()[index]
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
				ISingleParticleDrgnService.plotUmapColorlatentpca(job.jobId, dim)
			}
			plotUmapColorlatentpcaSize.fetch()

			// show images for the classes
			val imageSize = ImageSize.values()[classesPanel.index]
			for (classNum in classesRadio.checkedClasses) {

				classesElem.div(classes = setOf("class")) {

					div(classes = setOf("frame")) {
						val img = FetchImage {
							width = imageSize.approxWidth.px
							fetch(ISingleParticleDrgnService.classImagePathPca(job.jobId, dim, classNum, imageSize))
						}
						add(img)
						self.classImages.add(img)
						div("Class $classNum", classes = setOf("label"))
					}

					val mrcDownload = FileDownloadBadge(
						filetype = ".mrc file",
						url = ISingleParticleDrgnService.classMrcPathPca(job.jobId, dim, classNum),
						filename = "reconstruction_${job.shortNumberedName}_dim_${dim}_cls_${classNum}.mrc",
						loader = { Services.singleParticleDrgn.classMrcDataPca(job.jobId, dim, classNum) }
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

		private val dimensionsNav = this@SingleParticleDrgnView.dimensionsNav.clone()

		private val movie = ClassesMovie(
			job,
			imagePather = { classNum, size ->
				currentDimension()?.let { dim ->
					ISingleParticleDrgnService.classImagePathPca(job.jobId, dim, classNum, size)
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
							url = ISingleParticleDrgnService.classMrcPathPca(job.jobId, dim, classNum)
						)
					}
				}
		}

		fun close() {
			viewer.close()
		}
	}
}
