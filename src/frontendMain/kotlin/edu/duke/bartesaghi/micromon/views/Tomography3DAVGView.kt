package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.Tomography3DAVGNode
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink
import io.kvision.utils.px


fun Widget.onGoToTomography3DAVG(viewport: Viewport, project: ProjectData, job: Tomography3DAVGData) {
	onShow(Tomography3DAVGView.path(project, job)) {
		viewport.setView(Tomography3DAVGView(project, job))
	}
}


class Tomography3DAVGView(val project: ProjectData, val job: Tomography3DAVGData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/Tomography3DAVG/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomography3DAVG.get(jobId)
						viewport.setView(Tomography3DAVGView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: Tomography3DAVGData) = "/project/${project.owner.id}/${project.projectId}/Tomography3DAVG/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: Tomography3DAVGData) {
			routing.show(path(project, job))
			viewport.setView(Tomography3DAVGView(project, job))
		}
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "tomography-drgn-eval"))

	private var tabs: LazyTabPanel? = null
	private var averagesMapTab: AveragesMapTab? = null
	private var averagesThreeDeeTab: AveragesThreeDeeTab? = null

	private var testMetricFilterTab: TestMetricFilterTab? = null

	private var classesMapTab: ClassesMapTab? = null
	private var classesMovieTab: ClassesMovieTab? = null
	private var mrcThreeDeeTab: MrcThreeDeeTab? = null

	private var refinedClassesMapTab: RefinedClassesMapTab? = null
	private var refinedClassesMovieTab: RefinedClassesMovieTab? = null
	private var refinedMrcThreeDeeTab: RefinedMrcThreeDeeTab? = null

	// init the iteration nav
	private val iterations = ArrayList<Int>()
	private val iterationsNav = BigListNav(
		iterations,
		initialIndex = 0,
		initialLive = null, // no streaming, so hide the live options
		has100 = false
	)
	private fun currentIteration(): Int? =
		iterationsNav.currentIndex
			?.let { iterations[it] }

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink(project.numberedName, icon = "fas fa-project-diagram")
					.onGoToProject(project)
				navLink(job.numberedName, icon = Tomography3DAVGNode.type.iconClass)
					.onGoToTomography3DAVG(viewport, project, job)
			}
		}

		AppScope.launch {

			// load all the converenge, if any
			val loadingElem = elem.loading("Fetching analysis ...")
			val evalParams: Tomography3DAVGParams? = try {
				delayAtLeast(200) {
					Services.tomography3DAVG.getParams(job.jobId)
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

			// init the iterations
			iterations.clear()
			iterations.addAll(
				(1 .. evalParams.numIterations)
					.toList()
			)
			iterationsNav.newItem()

			// make the tabs panel
			tabs = elem.lazyTabPanel {

				persistence = Storage::tomography3DAVGTabIndex

				addTab("Global averages", "fas fa-circle") { lazyTab ->
					averagesMapTab = AveragesMapTab(evalParams.centeringIterations,evalParams.applySymmetry).also {
						lazyTab.elem.add(it)
						lazyTab.onActivate = { it.revalidate() }
					}
				}

				addTab("3D view (global averages)", "fas fa-cube") { lazyTab ->
					averagesThreeDeeTab = AveragesThreeDeeTab(evalParams.centeringIterations).also {
						lazyTab.elem.add(it)
					}
				}

				addTab("Classes", "fas fa-chart-pie") { lazyTab ->
					classesMapTab = ClassesMapTab(evalParams.classes).also {
						lazyTab.elem.add(it)
						lazyTab.onActivate = { it.revalidate() }
					}
				}

				addTab("Movie view", "fas fa-film") { lazyTab ->
					classesMovieTab = ClassesMovieTab(evalParams.classes).also {
						lazyTab.elem.add(it)
					}
				}

				addTab("3D view", "fas fa-cube") { lazyTab ->
					mrcThreeDeeTab = MrcThreeDeeTab(evalParams.classes).also {
						lazyTab.elem.add(it)
					}
				}

				addTab("Classes (aligned)", "fas fa-chart-pie") { lazyTab ->
					refinedClassesMapTab = RefinedClassesMapTab(evalParams.classes).also {
						lazyTab.elem.add(it)
						lazyTab.onActivate = { it.revalidate() }
					}
				}

				addTab("Movie view (aligned)", "fas fa-film") { lazyTab ->
					refinedClassesMovieTab = RefinedClassesMovieTab(evalParams.classes).also {
						lazyTab.elem.add(it)
					}
				}

				addTab("3D view (aligned)", "fas fa-cube") { lazyTab ->
					refinedMrcThreeDeeTab = RefinedMrcThreeDeeTab(evalParams.classes).also {
						lazyTab.elem.add(it)
					}
				}

				addTab("Test filter", "fas fa-filter") { lazyTab ->
					testMetricFilterTab = TestMetricFilterTab().also {
						lazyTab.elem.add(it)
						lazyTab.onActivate = { it.revalidate() }
					}
				}

			}
		}
	}

	override fun close() {
		mrcThreeDeeTab?.close()
		refinedMrcThreeDeeTab?.close()
		averagesThreeDeeTab?.close()
	}

	private inner class ClassesMapTab(
		numClasses: Int
	) : Div(classes = setOf("reconstruction-tab")) {

		private val classesRadio = ClassesRadio("Class", true)
		private val classesPanel = ContentSizedPanel(
			"Classes",
			ImageSize.values().map { it.approxWidth },
			(Storage.Tomography3DAVGClassSize ?: ImageSize.Small).ordinal
		)
		private val classesElem = Div(classes = setOf("classes"))
			.also { classesPanel.add(it) }

		private val iterationsNav = this@Tomography3DAVGView.iterationsNav.clone()

		private val plotImages = ArrayList<FetchImage>()
		private val classImages = ArrayList<FetchImage>()
		private val classMrcDownloads = ArrayList<FileDownloadBadge>()

		init {

			val self = this // Kotlin DSLs are dumb ...

			// layout the tab
			div(classes = setOf("nav")) {
				span("Iteration")
				add(self.iterationsNav)
				add(self.classesRadio)
			}
			add(classesPanel)

			// init classes
			classesRadio.count = numClasses
			classesRadio.checkedClasses = classesRadio.allClasses()

			// wire up events
			classesPanel.onResize = { index ->
				Storage.Tomography3DAVGClassSize = ImageSize.values()[index]
				updateClasses(numClasses)
			}
			classesRadio.onUpdate = {
				updateClasses(numClasses)
			}
			iterationsNav.onShow = {
				updateClasses(numClasses)
			}

			updateClasses(numClasses)
		}

		fun updateClasses(numClasses: Int) {

			classesElem.removeAll()
			classImages.clear()
			classMrcDownloads.clear()

			val iter = currentIteration()
				?: run {
					classesElem.emptyMessage("No iteration chosen")
					return
				}
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
							fetch(ITomography3DAVGService.classImagePath(job.jobId, iter, classNum, imageSize))
						}
						add(img)
						self.classImages.add(img)
						div("Class ${classNum}", classes = setOf("label"))
					}

					val formatted_iteration = "iteration_" + iter.toString().asDynamic().padStart(3, "0")
					val formatted_class = "average_" + classNum.toString().asDynamic().padStart(3, "0")

					val mrcDownload = FileDownloadBadge(
						filetype = ".mrc file",
						url = ITomography3DAVGService.classMrcPath(job.jobId, iter, classNum),
						filename = "${job.shortNumberedName}_${formatted_iteration}_level_${numClasses}_${formatted_class}.mrc",
						loader = { Services.tomography3DAVG.classMrcData(job.jobId, iter, classNum) }
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

	private inner class AveragesMapTab(
		val numIter: Int,
		applySymmetry: Boolean
	) : Div(classes = setOf("reconstruction-tab")) {

		private val iterationsPanel = ContentSizedPanel(
			"Unsymmetrized",
			ImageSize.values().map { it.approxWidth },
			(Storage.Tomography3DAVGClassSize ?: ImageSize.Small).ordinal
		)
		private val iterationsElem = Div(classes = setOf("classes"))
			.also { iterationsPanel.add(it) }

		private val iterationsSymmetrizedPanel = ContentSizedPanel(
			"Rotationally symmetrized",
			ImageSize.values().map { it.approxWidth },
			(Storage.Tomography3DAVGClassSize ?: ImageSize.Small).ordinal
		)
		private val iterationsSymmetrizedElem = Div(classes = setOf("classes"))
			.also { iterationsSymmetrizedPanel.add(it) }
		
		private val iterationImages = ArrayList<FetchImage>()
		private val iterationMrcDownloads = ArrayList<FileDownloadBadge>()

		private val iterationSymmetrizedImages = ArrayList<FetchImage>()
		private val iterationSymmetrizedMrcDownloads = ArrayList<FileDownloadBadge>()

		init {

			// val self = this // Kotlin DSLs are dumb ...
			
			add(iterationsPanel)
			if (applySymmetry) {
				add(iterationsSymmetrizedPanel)
			}

			// wire up events
			iterationsPanel.onResize = { index ->
				Storage.Tomography3DAVGClassSize = ImageSize.values()[index]
				updateIterations()
			}

			if (applySymmetry) {
				iterationsSymmetrizedPanel.onResize = { index ->
					Storage.Tomography3DAVGClassSize = ImageSize.values()[index]
					updateIterationsSymmetrized()
				}
			}
			
			updateIterations()
			
			if (applySymmetry) {
				updateIterationsSymmetrized()
			}
		}

		fun updateIterations() {

			iterationsElem.removeAll()
			iterationImages.clear()
			iterationMrcDownloads.clear()

			val self = this // Kotlin DSLs are dumb ...

			// show images for the iterations
			val imageSize = ImageSize.values()[iterationsPanel.index]
			for (iterNum in 0 .. numIter) {

				iterationsElem.div(classes = setOf("class")) {

					div(classes = setOf("frame")) {
						val img = FetchImage {
							width = imageSize.approxWidth.px
							fetch(ITomography3DAVGService.averageImagePath(job.jobId, iterNum, imageSize))
						}
						add(img)
						self.iterationImages.add(img)
						div("Iteration $iterNum", classes = setOf("label"))
					}

					val mrcDownload = FileDownloadBadge(
						filetype = ".mrc file",
						url = ITomography3DAVGService.averageMrcPath(job.jobId, iterNum),
						filename = "${job.shortNumberedName}_global_average_${iterNum}.mrc",
						loader = { Services.tomography3DAVG.averageMrcData(job.jobId, iterNum) }
					)
					add(mrcDownload)
					self.iterationMrcDownloads.add(mrcDownload)
					mrcDownload.load()
				}
			}
		}

		fun updateIterationsSymmetrized() {

			iterationsSymmetrizedElem.removeAll()
			iterationSymmetrizedImages.clear()
			iterationSymmetrizedMrcDownloads.clear()

			val self = this // Kotlin DSLs are dumb ...

			// show images for the iterations
			val imageSize = ImageSize.values()[iterationsSymmetrizedPanel.index]
			for (iterNum in 0 .. numIter) {

				iterationsSymmetrizedElem.div(classes = setOf("class")) {

					div(classes = setOf("frame")) {
						val img = FetchImage {
							width = imageSize.approxWidth.px
							fetch(ITomography3DAVGService.averageSymmetrizedImagePath(job.jobId, iterNum, imageSize))
						}
						add(img)
						self.iterationSymmetrizedImages.add(img)
						div("Iteration $iterNum", classes = setOf("label"))
					}

					val mrcDownload = FileDownloadBadge(
						filetype = ".mrc file",
						url = ITomography3DAVGService.averageSymmetrizedMrcPath(job.jobId, iterNum),
						filename = "${job.shortNumberedName}_global_average_${iterNum}_symmetrized.mrc",
						loader = { Services.tomography3DAVG.averageSymmetrizedMrcData(job.jobId, iterNum) }
					)
					add(mrcDownload)
					self.iterationSymmetrizedMrcDownloads.add(mrcDownload)
					mrcDownload.load()
				}
			}
		}

		fun revalidate() {
			for (img in iterationImages) {
				img.revalidate()
			}
			for (img in iterationSymmetrizedImages) {
				img.revalidate()
			}
			for (download in iterationMrcDownloads) {
				download.load()
			}
			for (download in iterationSymmetrizedMrcDownloads) {
				download.load()
			}
		}
	}
	
	private inner class TestMetricFilterTab(
	) : Div(classes = setOf("reconstruction-tab")) {

		private val filteredPanel = ContentSizedPanel(
			"Filtered map",
			ImageSize.values().map { it.approxWidth },
			(Storage.Tomography3DAVGClassSize ?: ImageSize.Small).ordinal
		)
		private val filteredElem = Div(classes = setOf("classes"))
			.also { filteredPanel.add(it) }

		private val filteredImages = ArrayList<FetchImage>()
		private val filteredMrcDownloads = ArrayList<FileDownloadBadge>()

		init {

			// val self = this // Kotlin DSLs are dumb ...
			
			add(filteredPanel)

			// wire up events
			filteredPanel.onResize = { index ->
				Storage.Tomography3DAVGClassSize = ImageSize.values()[index]
				updateFiltered()
			}

			updateFiltered()
		}

		fun updateFiltered() {

			filteredElem.removeAll()
			filteredImages.clear()
			filteredMrcDownloads.clear()

			val self = this // Kotlin DSLs are dumb ...

			// show images for the iterations
			val imageSize = ImageSize.values()[filteredPanel.index]
			filteredElem.div(classes = setOf("class")) {
				div(classes = setOf("frame")) {
					val img = FetchImage {
						width = imageSize.approxWidth.px
						fetch(ITomography3DAVGService.testMetricFilterImagePath(job.jobId, imageSize))
					}
					add(img)
					self.filteredImages.add(img)
				}

				val mrcDownload = FileDownloadBadge(
					filetype = ".mrc file",
					url = ITomography3DAVGService.testMetricFilterMrcPath(job.jobId),
					filename = "${job.shortNumberedName}_test_metric_filtered.mrc",
					loader = { Services.tomography3DAVG.testMetricFilterMrcData(job.jobId) }
				)
				add(mrcDownload)
				self.filteredMrcDownloads.add(mrcDownload)
				mrcDownload.load()
			}
		}

		fun revalidate() {
			for (img in filteredImages) {
				img.revalidate()
			}
			for (download in filteredMrcDownloads) {
				download.load()
			}
		}
	}
	
	private inner class RefinedClassesMapTab(
		numClasses: Int
	) : Div(classes = setOf("reconstruction-tab")) {

		private val classesRadio = ClassesRadio("Class", true)
		private val classesPanel = ContentSizedPanel(
			"Classes",
			ImageSize.values().map { it.approxWidth },
			(Storage.Tomography3DAVGClassSize ?: ImageSize.Small).ordinal
		)
		private val classesElem = Div(classes = setOf("classes"))
			.also { classesPanel.add(it) }

		private val iterationsNav = this@Tomography3DAVGView.iterationsNav.clone()

		private val plotImages = ArrayList<FetchImage>()
		private val classImages = ArrayList<FetchImage>()
		private val classMrcDownloads = ArrayList<FileDownloadBadge>()

		init {

			val self = this // Kotlin DSLs are dumb ...

			// layout the tab
			div(classes = setOf("nav")) {
				span("Iteration")
				add(self.iterationsNav)
				add(self.classesRadio)
			}
			add(classesPanel)

			// init classes
			classesRadio.count = numClasses
			classesRadio.checkedClasses = classesRadio.allClasses()

			// wire up events
			classesPanel.onResize = { index ->
				Storage.Tomography3DAVGClassSize = ImageSize.values()[index]
				updateClasses(numClasses)
			}
			classesRadio.onUpdate = {
				updateClasses(numClasses)
			}
			iterationsNav.onShow = {
				updateClasses(numClasses)
			}

			updateClasses(numClasses)
		}

		fun updateClasses(numClasses: Int) {

			classesElem.removeAll()
			classImages.clear()
			classMrcDownloads.clear()

			val iter = currentIteration()
				?: run {
					classesElem.emptyMessage("No iteration chosen")
					return
				}
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
							fetch(ITomography3DAVGService.refinedClassImagePath(job.jobId, iter, classNum, imageSize))
						}
						add(img)
						self.classImages.add(img)
						div("Class ${classNum}", classes = setOf("label"))
					}

					val	formatted_iteration = "iteration_" + iter.toString().asDynamic().padStart(3, "0")
					val	formatted_class = "average_" + classNum.toString().asDynamic().padStart(3, "0")

					val mrcDownload = FileDownloadBadge(
						filetype = ".mrc file",
						url = ITomography3DAVGService.classMrcPath(job.jobId, iter, classNum),
						filename = "${job.shortNumberedName}_${formatted_iteration}_refined_level_${numClasses}_${formatted_class}.mrc",
						loader = { Services.tomography3DAVG.refinedClassMrcData(job.jobId, iter, classNum) }
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

	private inner class ClassesMovieTab(
		val numClasses: Int
	) : Div() {

		private val iterationsNav = this@Tomography3DAVGView.iterationsNav.clone()

		private val movie = ClassesMovie(
			job,
			imagePather = { classNum, size ->
				currentIteration()?.let { iter ->
					ITomography3DAVGService.classImagePath(job.jobId, iter, classNum, size)
				}
				?: ""
			}
		)

		init {

			// layout
			add(iterationsNav)
			iterationsNav.onShow = {
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


	private inner class RefinedClassesMovieTab(
		val numClasses: Int
	) : Div() {

		private val iterationsNav = this@Tomography3DAVGView.iterationsNav.clone()

		private val movie = ClassesMovie(
			job,
			imagePather = { classNum, size ->
				currentIteration()?.let { iter ->
					ITomography3DAVGService.refinedClassImagePath(job.jobId, iter, classNum, size)
				}
				?: ""
			}
		)

		init {

			// layout
			add(iterationsNav)
			iterationsNav.onShow = {
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

	private inner class MrcThreeDeeTab(
		val numClasses: Int
	) : Div() {

		private val viewer = ThreeDeeViewer()

		init {
			// layout
			add(viewer)

			// init the volumes
			viewer.volumes =
				(1 .. iterations.size).flatMap { iter ->
					(1 .. numClasses).map { classNum ->
						ThreeDeeViewer.VolumeData(
							name = "Iteration $iter, Class $classNum",
							url = ITomography3DAVGService.classMrcPath(job.jobId, iter, classNum)
						)
					}
				}
			}

		fun close() {
			viewer.close()
		}
	}

	private inner class RefinedMrcThreeDeeTab(
		val numClasses: Int
	) : Div() {

		private val viewer = ThreeDeeViewer()

		init {
			// layout
			add(viewer)

			// init the volumes
			viewer.volumes =
				(1 .. iterations.size).flatMap { iter ->
					(1 .. numClasses).map { classNum ->
						ThreeDeeViewer.VolumeData(
							name = "Iteration $iter, Class $classNum",
							url = ITomography3DAVGService.refinedClassMrcPath(job.jobId, iter, classNum)
						)
					}
				}
			}

		fun close() {
			viewer.close()
		}
	}

	private inner class AveragesThreeDeeTab(
		val numIterations: Int
	) : Div() {

		private val viewer = ThreeDeeViewer()

		init {
			// layout
			add(viewer)

			// init the volumes
			viewer.volumes =
				(0 .. numIterations).map { iter ->
					ThreeDeeViewer.VolumeData(
						name = "Unsymmetrized average, Iteration $iter",
						url = ITomography3DAVGService.averageMrcPath(job.jobId, iter)
					)
				}
			}

		fun close() {
			viewer.close()
		}
	}

}
