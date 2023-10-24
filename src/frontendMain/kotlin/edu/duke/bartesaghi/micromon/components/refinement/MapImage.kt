package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.SizedPanel
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.html.Image
import io.kvision.html.div


/**
 * Shows the map image in a resizable panel.
 * Modified from ParticlesImage.kt
 */
class MapImage(
	val jobId: String,
	private val reconstruction: ReconstructionData
) : SizedPanel("Projections/Slices", Storage.micrographSize) {

	fun ImageSize.url() =
		"/kv/reconstructions/${jobId}/${reconstruction.classNum}/${reconstruction.iteration}/images/map/$id"

	private val imageElem = Image("")

	private val imageContainerElem = div {
		add(this@MapImage.imageElem)
	}

	init {

		// add all the components to the panel
		add(imageContainerElem)

		// load the micrograph image
		imageElem.src = size.url()

		// set the panel resize handler
		onResize = { newSize: ImageSize ->

			// save the new size
			Storage.micrographSize = newSize

			// update the image
			imageElem.src = newSize.url()

		}.apply {
			addCssClass("micrograph-panel")
		}
	}
}
