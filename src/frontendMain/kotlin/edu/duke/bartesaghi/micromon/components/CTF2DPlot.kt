package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.Storage
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.toFixed
import io.kvision.html.Div
import io.kvision.html.Image
import io.kvision.html.div


open class CTF2DPlot(
	datum: PreprocessingData,
	val imageUrl: (ImageSize) -> String
) : SizedPanel("2D CTF Profile", Storage.ctffindSize, includeLarge = false) {

	private val image = Image("")

	init {

		div {
			div("DF1 ${datum.defocus1.toFixed(1)} A, DF2 ${datum.defocus2.toFixed(1)} A")
			div("Angast ${datum.angleAstig.toFixed(1)}\u00b0, Score ${datum.ccc.toFixed(2)}")
		}

		onResize = { newSize ->

			// save the new size
			Storage.ctffindSize = newSize

			// update the image
			image.src = imageUrl(newSize)

		}

		image.src = imageUrl(size)
		add(image)
	}
}


class Micrograph2DPlot(
	val job: JobData,
	val micrograph: MicrographMetadata
) : CTF2DPlot(
	micrograph,
	imageUrl = { size -> micrograph.ctffindUrl(job, size) },
)


class TiltSeries2DPlot(
	val job: JobData,
	val tiltSeries: TiltSeriesData
) : CTF2DPlot(
	tiltSeries,
	imageUrl = { size -> tiltSeries.ctffindUrl(job, size) }
)


class SessionMicrograph2DPlot(
	session: SessionData,
	val micrograph: MicrographMetadata
) : CTF2DPlot(
	micrograph,
	imageUrl = { size -> micrograph.ctffindUrl(session, size) }
)


class SessionTiltSeries2DPlot(
	session: SessionData,
	val tiltSeries: TiltSeriesData
) : CTF2DPlot(
	tiltSeries,
	imageUrl = { size -> tiltSeries.ctffindUrl(session, size) }
)
