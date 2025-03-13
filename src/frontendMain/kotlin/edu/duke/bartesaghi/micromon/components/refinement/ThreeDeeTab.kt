package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.components.ThreeDeeViewer
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.PathableTab
import edu.duke.bartesaghi.micromon.views.RegisterableTab
import edu.duke.bartesaghi.micromon.views.TabRegistrar
import io.kvision.html.*


class ThreeDeeTab(
	private val reconstructions: List<ReconstructionData>,
	private val job: JobData
) : Div(), PathableTab {

	companion object : RegisterableTab {

		const val pathFragment = "3D"

		override fun registerRoutes(register: TabRegistrar) {
			register(pathFragment) {
				null
			}
		}
	}

	override var onPathChange = {}
	override var isActiveTab = false

	private val viewer = ThreeDeeViewer()

    init {
		// layout the tab
		add(viewer)

		// set the volumes
		viewer.volumes = reconstructions
			.mapIndexed { i, rec ->
				ThreeDeeViewer.VolumeData(
					name = "Class ${rec.classNum}, Iter ${rec.iteration}",
					url = "/kv/reconstructions/${job.jobId}/${rec.classNum}/${rec.iteration}/map/${MRCType.CROP.id}"
				)
			}
			.reversed()
    }

	override fun path(): String =
		pathFragment

	fun close() {
		viewer.close()
	}
}
