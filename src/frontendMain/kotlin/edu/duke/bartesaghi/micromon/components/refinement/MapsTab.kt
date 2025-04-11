package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.*
import io.kvision.core.onEvent
import io.kvision.html.*
import io.kvision.toast.Toast
import kotlin.js.Date


class MapsTab(
	val job: JobData,
	val state: IntegratedRefinementView.State,
	urlParams: UrlParams?,
	val showPerParticleScores: Boolean
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
	private val classRadio = ClassesRadio("Class", false)
	private val contentElem = Div()


	init {

		// init the classes
		val allClasses = state.currentIteration
			?.let { state.reconstructions.withIteration(it) }
			?.classes
			?: emptyList()
		classRadio.count = allClasses.size

		// apply the URL params, if any
		if (urlParams != null) {
			applyUrlParams(urlParams)
		} else {

			// otherwise, leave the iteration alone (it's shared with other tabs),
			// but start by showing the first class (only used by this tab), if any
			classRadio.checkedClasses = allClasses.firstOrNull()
				?.let { listOf(it) }
				?: emptyList()
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
		classRadio.checkedClasses = listOf(classNum)
	}

	override fun path(): String {
		val iteration = state.currentIteration
		val classNum = classRadio.checkedClasses
			.firstOrNull()
		return path(iteration, classNum)
	}

	fun update() {

		// clear the previous contents
		contentElem.removeAll()

		val iteration = state.currentIteration
			?: run {
				contentElem.emptyMessage("No iteration selected")
				return
			}

		// update the classes radio
		classRadio.count = state.reconstructions.withIteration(iteration)?.classes?.size ?: 0

		val classNum = classRadio.checkedClasses
			.firstOrNull()
			?: run {
				contentElem.emptyMessage("No class selected")
				return
			}

		val reconstruction = state.reconstructions
			.withIteration(iteration)
			?.withClass(classNum)
			?: run {
				contentElem.emptyMessage("No reconstruction to show")
				return
			}

		showReconstruction(reconstruction)
	}

	fun show(reconstruction: ReconstructionData) {

		// clear the previous contents
		contentElem.removeAll()

		// update the classes radio
		classRadio.count = state.reconstructions.withIteration(reconstruction.iteration)?.classes?.size ?: 0
		classRadio.checkedClasses = listOf(reconstruction.classNum)

		showReconstruction(reconstruction)
	}

	private fun showReconstruction(reconstruction: ReconstructionData) {

		// Make the downloads button
		val lastIteration = state.reconstructions.iterations
			.lastOrNull()
		contentElem.add(MapDownloads(job, reconstruction, when (reconstruction.iteration) {
			lastIteration -> MRCType.values().toList()
			else -> listOf(MRCType.CROP, MRCType.FULL)
		}))

		// show metadata
		val me = this
		contentElem.div(classes = setOf("reconstruction-stats")) {
			div {
				span("Showing refinement Iteration ${reconstruction.iteration}, Class ${reconstruction.classNum}, completed on ${Date(reconstruction.timestamp).toLocaleString()}")
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

		// show the per-particle scores, if needed
		if (showPerParticleScores) {
			val panel = SizedPanel(
				"Per-Particle Scores",
				Storage.perParticleScoresSize ?: ImageSize.Medium
			)
			var exists = true
			val img = panel.image("/kv/reconstructions/${job.jobId}/${reconstruction.classNum}/${reconstruction.iteration}/images/perParticleScores")
			fun resizeImage() {
				if (exists) {
					img.setStyle("width", "${panel.size.approxWidth}px")
				} else {
					img.src = "img/placeholder/${panel.size}"
					img.removeStyle("width")
				}
			}
			resizeImage()
			panel.onResize = { size ->
				Storage.perParticleScoresSize = size
				resizeImage()
			}
			img.onEvent {
				error = {
					// if the image load fails, remember that it doesn't exist so we can do something else
					if (exists) {
						exists = false
						resizeImage()
					}
				}
			}
			contentElem.add(panel)
		}

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
			hist.data = OrientationDefocusPlots.Data(
				job,
				reconstruction,
				reconstructionPlotData
			)
			hist.update()
			plot.data = reconstructionPlotData
			plot.update()
		}
	}

	fun showClass(classNum: Int) {
		classRadio.checkedClasses = listOf(classNum)
		update()
	}
}
