package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.errorMessage
import edu.duke.bartesaghi.micromon.services.JobData
import edu.duke.bartesaghi.micromon.services.MRCType
import edu.duke.bartesaghi.micromon.services.ReconstructionData
import edu.duke.bartesaghi.micromon.services.Services
import edu.duke.bartesaghi.micromon.views.IntegratedRefinementView
import edu.duke.bartesaghi.micromon.views.PathableTab
import edu.duke.bartesaghi.micromon.views.RegisterableTab
import edu.duke.bartesaghi.micromon.views.TabRegistrar
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.toast.Toast


class MapTab(
	val job: JobData,
	val state: IntegratedRefinementView.State,
	val show_fsc: Boolean
) : Div(), PathableTab {

	companion object : RegisterableTab {

		const val pathFragment = "map"

		override fun registerRoutes(register: TabRegistrar) {

			register(pathFragment) {
				null
			}
		}
	}

	override var onPathChange = {}
	override var isActiveTab = false

	init {
		update()
	}

	fun update() {

		// clear the previous contents
		removeAll()

		fun errmsg(msg: String) =
			div(msg, classes = setOf("empty", "spaced"))

		// these blocks only create a single reconstruction,
		// so we don't need to pay attention to iterations and classes
		val reconstruction = state.reconstructions.all().lastOrNull()
			?: run {
				errmsg("No reconstruction to show")
				return
			}

		show(reconstruction)
	}

	private fun show(reconstruction: ReconstructionData) {

		// Make the downloads button
		add(MapDownloads(job, reconstruction, listOf(MRCType.CROP, MRCType.FULL)))

		// show metadata
		add(MapImage(job.jobId, reconstruction))

		val fsc = if (show_fsc == true){
			MaskedFSCPlot()
				.also { add(it) }
		} else {
			null
		}

		if (fsc != null){
			AppScope.launch {

				val reconstructionPlotData = try {
					Services.integratedRefinement.getReconstructionPlotsData(job.jobId, reconstruction.id)
				} catch (t: Throwable) {
					Toast.errorMessage(t, "Failed to load reconstruction plot data")
					return@launch
				}

				fsc.data = reconstructionPlotData
				fsc.update()
			}
		}
	}

	override fun path() =
		pathFragment
}
