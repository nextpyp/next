package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.services.JobData
import edu.duke.bartesaghi.micromon.services.MRCType
import edu.duke.bartesaghi.micromon.services.Services
import edu.duke.bartesaghi.micromon.views.*
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.toast.Toast
import kotlin.js.Date


class MapsTab(
	val job: JobData,
	val state: IntegratedRefinementView.State,
	urlParams: UrlParams?,
) : Div(classes = setOf("maps-tab")), PathableTab {

	data class UrlParams(
		val iteration: String,
		val classNum: String
	)

	companion object : RegisterableTab {

		const val pathFragment = "maps"

		override fun registerRoutes(register: TabRegistrar) {

			register(pathFragment) {
				null
			}

			register("$pathFragment/it($urlToken)/cl($urlToken)$") { params ->
				UrlParams(
					iteration = params[0],
					classNum = params[1]
				)
			}
		}

		fun path(iteration: Int?, classNum: Int?): String =
			pathFragment + if (iteration != null && classNum != null) {
				"/it$iteration/cl$classNum"
			} else {
				""
			}
	}

	override var onPathChange = {}
	override var isActiveTab = false

	private val iterationNav = state.iterationNav.clone()
	private val classRadio = RadioSelection(
		labelText = "Class: "
	)
	private val contentElem = Div()


	init {

		// init the classes
		val allClasses = state.currentIteration
			?.let { state.reconstructions.withIteration(it) }
			?.classes
			?: emptyList()
		classRadio.setCount(allClasses.size)

		// apply the URL params, if any
		if (urlParams != null) {
			applyUrlParams(urlParams)
		} else {

			// otherwise, leave the iteration alone (it's shared with other tabs),
			// but start by showing the first class (only used by this tab), if any
			val selectedClasses =
				allClasses.firstOrNull()
					?.let { listOf(it) }
					?: emptyList()
			classRadio.setCheckedIndices(selectedClasses.map { it.classNumToIndex() })
		}

		// build the UI
		div(classes = setOf("tabnav")) {
			add(this@MapsTab.iterationNav)
			add(this@MapsTab.classRadio)
		}
		add(contentElem)

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

		// apply the class
		val classes = state.reconstructions.withIteration(iteration)
			?.classes
			?.takeIf { it.isNotEmpty() }
			?: return
		val classNum = params.classNum
			.toIntOrNull()
			?.takeIf { it >= classes.first() && it <= classes.last() }
			?: return
		classRadio.setCheckedIndices(listOf(classNum.classNumToIndex()))
	}

	override fun path(): String {
		val iteration = state.currentIteration
		val classNum = classRadio.getCheckedIndices()
			.firstOrNull()
			?.indexToClassNum()
		return path(iteration, classNum)
	}

	fun update() {

		// clear the previous contents
		contentElem.removeAll()

		val iterations = state.reconstructions.iterations
			.takeIf { it.isNotEmpty() }
			?: run {
				emptyMessage("No reconstructions to show")
				return
			}

		val iteration = state.currentIteration
			?: run {
				emptyMessage("No iteration selected")
				return
			}

		// update the classes radio
		classRadio.setCount(state.reconstructions.withIteration(iteration)?.classes?.size ?: 0)

		val classNum = classRadio.getCheckedIndices()
			.firstOrNull()
			?.indexToClassNum()
			?: run {
				emptyMessage("No class selected")
				return
			}

		val reconstruction = state.reconstructions
			.withIteration(iteration)
			?.withClass(classNum)
			?: run {
				emptyMessage("No reconstruction to show")
				return
			}

		// Make the downloads button
		contentElem.add(MapDownloads(job, reconstruction, when (iteration) {
			iterations.last() -> MRCType.values().toList()
			else -> listOf(MRCType.HALF1, MRCType.HALF2)
		}))

		// show metadata
		val me = this
		contentElem.div(classes = setOf("reconstruction-stats")) {
			div {
				span("Showing refinement Iteration $iteration, Class $classNum, completed on ${Date(reconstruction.timestamp).toLocaleString()}")
				button("Show Log", classes = setOf("log-button")).onClick {
					LogView.showPopup("Log for Reconstruction: ${reconstruction.id}") {
						Services.integratedRefinement.getLog(me.job.jobId, reconstruction.id)
					}
				}
			}
		}
		contentElem.add(MapImage(job.jobId, reconstruction))

		val fsc = FSCPlot()
			.also { contentElem.add(it) }
		val hist = OrientationDefocusPlots()
			.also { contentElem.add(it) }
		val plot = RefinementStatistics()
			.also { contentElem.add(it) }

		AppScope.launch {

			val loading = loading("Loading plots ...")
			val reconstructionPlotData = try {
				Services.integratedRefinement.getReconstructionPlotsData(job.jobId, reconstruction.id)
			} catch (t: Throwable) {
				Toast.errorMessage(t, "Failed to load reconstruction plot data")
				return@launch
			} finally {
				remove(loading)
			}

			fsc.data = reconstructionPlotData
			fsc.update()
			hist.data = reconstructionPlotData
			hist.update()
			plot.data = reconstructionPlotData
			plot.update()
		}
	}

	fun showClass(classNum: Int) {
		classRadio.setCheckedIndices(listOf(classNum.classNumToIndex()))
		update()
	}
}
