package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.services.JobData
import edu.duke.bartesaghi.micromon.services.MRCType
import edu.duke.bartesaghi.micromon.services.ReconstructionData
import io.kvision.core.onEvent
import io.kvision.form.select.simpleSelect
import io.kvision.html.Div
import kotlinx.browser.window


class MapDownloads(
	val job: JobData,
	val reconstruction: ReconstructionData,
	types: List<MRCType>
) : Div(classes = setOf("reconstruction-options")) {

	companion object {
		const val nopValue = ""
	}

	private fun MRCType.label(): String =
		when (this) {
			MRCType.FULL  -> "Full-Size Map"
			MRCType.CROP  -> "Cropped Map"
			MRCType.HALF1 -> "Half-Map 1"
			MRCType.HALF2 -> "Half-Map 2"
		}

	private val selector = simpleSelect(
		options = listOf(nopValue to "Select an MRC file to download")
			+ types.map { it.name to it.label() },
		value = nopValue
	).apply {

		// wire up events
		onEvent {
			change = e@{ _ ->

				// get the selected type, if any
				val type = MRCType.values()
					.find { it.name == value }
					?: return@e

				// remove the selection
				value = nopValue

				// download the MRC
				val url = "/kv/reconstructions/${job.jobId}/${reconstruction.classNum}/${reconstruction.iteration}/map/${type.id}"
				window.open(url, "_blank")
			}
		}
	}
}
