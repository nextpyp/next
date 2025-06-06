package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.ClassesRadio
import edu.duke.bartesaghi.micromon.components.ContentSizedPanel
import edu.duke.bartesaghi.micromon.services.ImageSize
import edu.duke.bartesaghi.micromon.services.JobData
import edu.duke.bartesaghi.micromon.services.ReconstructionData
import edu.duke.bartesaghi.micromon.services.Services
import edu.duke.bartesaghi.micromon.views.*
import io.kvision.html.*


class ClassesTab(
	val view: IntegratedRefinementView,
	val job: JobData,
	val state: IntegratedRefinementView.State,
	urlParams: UrlParams?
) : Div(classes = setOf("classes-tab")), PathableTab {

	data class UrlParams(
		val iteration: String,
		val classNums: String
	)

	companion object : RegisterableTab {

		const val pathFragment = "classes"

		override fun registerRoutes(register: TabRegistrar) {

			register(pathFragment) {
				null
			}

			register("$pathFragment/it($urlToken)/cls($urlToken)$") { params ->
				UrlParams(
					iteration = params[0],
					classNums = params[1]
				)
			}

			// NOTE: navigo won't match the classes if they're empty, so register another URL without them
			register("$pathFragment/it($urlToken)/cls$") { params ->
				UrlParams(
					iteration = params[0],
					classNums = ""
				)
			}
		}
	}

	override var onPathChange = {}
	override var isActiveTab = false

	private val iterationNav = state.iterationNav.clone()

	private val classRadio = ClassesRadio("Classes", true)

	private val thumbsPanel = ContentSizedPanel(
		"Projections/Slices",
		ImageSize.values().map { it.approxWidth },
		Storage.classViewImageSize?.ordinal
	)
		.apply {
			onResize = { index ->
				Storage.classViewImageSize = ImageSize.values()[index]
				this@ClassesTab.updateThumbnails()
			}
		}
	private val thumbsElem = Div(classes = setOf("thumbnails"))
		.also { thumbsPanel.add(it) }


	private val selectedReconstructions = ArrayList<ReconstructionData>()
	private val fscPlot = ClassFSCPlot()
	private val occupancyPlot = ClassOccupancyPlot()


	init {

		val classes = state.currentIteration
			?.let { state.reconstructions.withIteration(it) }
			?.classes
			?: emptyList()
		classRadio.count = classes.size

		// apply the URL params, if any
		if (urlParams != null) {
			applyUrlParams(urlParams)
		} else {

			// otherwise, leave the iteration alone (it's shared with other tabs),
			// but start by showing all classes
			classRadio.checkedClasses = classes
		}

		// layout the UI
		val me = this@ClassesTab
		div {

			div(classes = setOf("tabnav")) {
				add(me.iterationNav)
				add(me.classRadio)
			}

			div(classes = setOf("reconstruction-thumbs")) {
				add(me.thumbsPanel)
			}

			div {
				add(me.fscPlot)
				add(me.occupancyPlot)
			}
		}

		update()

		// wire up events to listen to any future changes
		iterationNav.onShow = {
			update()
			// only update the path if we're the active tab, to prevent races
			if (isActiveTab) {
				onPathChange()
			}
		}
		classRadio.onUpdate = {
			update()
			onPathChange()
		}
	}

	private fun applyUrlParams(params: UrlParams) {

		val iterations = state.reconstructions.iterations
			.takeIf { it.isNotEmpty() }
			?: return

		// apply the iteration
		val iteration = params.iteration
			.toIntOrNull()
			?.takeIf { it >= iterations.first() && it <= iterations.last() }
			?: return
		state.currentIteration = iteration

		// apply the classes
		val allClasses = state.reconstructions.withIteration(iteration)
			?.classes
			?.takeIf { it.isNotEmpty() }
			?: return
		val selectedClasses = params.classNums
			.split(',')
			.mapNotNull { it.toIntOrNull() }
			.filter { it in allClasses }
		classRadio.checkedClasses = selectedClasses
	}

	override fun path(): String {
		val iteration = state.currentIteration
		val classNums = classRadio.checkedClasses
		return pathFragment + if (iteration != null) {
			"/it$iteration/cls${classNums.joinToString(",")}"
		} else {
			""
		}
	}

	private fun update() {

		// clear any previous UI
		thumbsElem.removeAll()
		fscPlot.data = emptyList()
		fscPlot.selectedClasses = emptyList()
		fscPlot.update()
		occupancyPlot.data = emptyList()
		occupancyPlot.selectedClasses = emptyList()
		occupancyPlot.update()

		val iteration = state.currentIteration
			?: run {
				thumbsElem.emptyMessage("No iteration selected")
				return
			}

		// update the classes radio
		classRadio.count = state.reconstructions.withIteration(iteration)?.classes?.size ?: 0

		val classes = classRadio.checkedClasses
			.takeIf { it.isNotEmpty() }
			?: run {
				thumbsElem.emptyMessage("No classes selected")
				return
			}

		val reconstructions = state.reconstructions
			.withIteration(iteration)
			?.all()
			?: run {
				thumbsElem.emptyMessage("No reconstructions to show")
				return
			}

		// filter the reconstructions by class
		selectedReconstructions.clear()
		for (reconstruction in reconstructions) {
			if (reconstruction.classNum in classes) {
				selectedReconstructions.add(reconstruction)
			}
		}
		updateThumbnails()

		AppScope.launch {

			// load the plots data
			val loading = loading("Loading data ...")
			val plotsData = try {
				reconstructions.map { Services.integratedRefinement.getReconstructionPlotsData(job.jobId, it.id) }
			} catch (t: Throwable) {
				errorMessage(t)
				return@launch
			} finally {
				remove(loading)
			}

			// update the plots
			fscPlot.data = plotsData
			fscPlot.selectedClasses = classes
			fscPlot.update()
			occupancyPlot.data = plotsData
			occupancyPlot.selectedClasses = classes
			occupancyPlot.update()

			// wire up events
			fun onClassClick(classNum: Int) {
				classRadio.toggleClass(classNum)
			}

			fun onClassDoubleClick(classNum: Int) {
				classRadio.toggleFocusClass(classNum)
			}

			fscPlot.onClick = ::onClassClick
			fscPlot.onDoubleClick = ::onClassDoubleClick
			occupancyPlot.onClick = ::onClassClick
			occupancyPlot.onDoubleClick = ::onClassDoubleClick
		}
	}

	private fun updateThumbnails() {

		thumbsElem.removeAll()

		val jobId = job.jobId
		val imageSize = ImageSize.values()[thumbsPanel.index]

		// update the map thumbnails
		for (reconstruction in selectedReconstructions) {
			thumbsElem
				.div(classes = setOf("thumbnail")) {
					image("/kv/reconstructions/$jobId/${reconstruction.classNum}/${reconstruction.iteration}/images/map/${imageSize.id}")
					span(content = "Class ${reconstruction.classNum}")
				}
				.onClick {
					view.showMap(reconstruction.classNum)
				}
		}
	}
}
