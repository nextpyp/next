package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.*
import js.plotly.plot
import kotlinext.js.jsObject
import io.kvision.html.div
import io.kvision.html.span
import kotlin.math.abs


open class CTF1DPlot(
	val loader: suspend () -> AvgRotData?,
	val titler: () -> String
) : SizedPanel("1D CTF Profile", Storage.avgRotSize) {

	private var avgrot: AvgRotData? = null
	private val container = div()

	init {

		addHeader()

		onResize = { newSize: ImageSize ->

			// save the selection in storage
			Storage.avgRotSize = newSize

			replot()
		}
	}

	fun loadData() {

		// gather the data to show the avgrot plot
		AppScope.launch {

			val loadingElem = loading("Fetching data ...")
			val avgrot: AvgRotData? = try {
				delayAtLeast(100) {
					loader()
				}
			} catch (t: Throwable) {
				errorMessage(t)
				null
			} finally {
				remove(loadingElem)
			}

			setData(avgrot)
		}
	}

	fun setData(avgrot: AvgRotData?) {
		this.avgrot = avgrot
		replot()
	}

	private fun addHeader() {
		// can't use implicit receivers inside of DSL blocks anymore =(
		val me = this
		container.div {
			span(me.titler())
		}
	}

	private fun replot() {

		container.removeAll()
		addHeader()

		val avgrot = avgrot
		if (avgrot == null) {
			container.div("(No CTF data)", classes = setOf("empty"))
			return
		}

		// find the index of the min res (or the closest sample to it)
		val minFreq = 1.0/avgrot.minRes
		val iMinRes = avgrot.spatialFreq
			.withIndex()
			.minByOrNull { (_, res) -> abs(res - minFreq) }
			?.index
			?: 0

		val count = avgrot.spatialFreq.size
		val spatialFreq = avgrot.spatialFreq.subList(iMinRes, count).toTypedArray()

		container.plot(
			jsObject {
				x = spatialFreq
				y = avgrot.avgRot.subList(iMinRes, count).toTypedArray()
				mode = "lines"
				name = "Radial average"
				line = jsObject {
					color = Palette.red
				}
			},
			jsObject {
				x = spatialFreq
				y = avgrot.ctfFit.subList(iMinRes, count).toTypedArray()
				mode = "lines"
				name = "Estimated CTF"
				line = jsObject {
					color = Palette.green
				}
			},
			jsObject {
				x = spatialFreq
				y = avgrot.crossCorrelation.subList(iMinRes, count).toTypedArray()
				mode = "lines"
				name = "Quality Fit"
				line = jsObject {
					color = Palette.blue
				}
			},

			layout = jsObject {
				autosize = true
				height = when (size) {
					ImageSize.Small -> 250
					ImageSize.Medium -> 300
					ImageSize.Large -> 350
				}
				yaxis = jsObject {
					range = arrayOf(0.0, 1.2)
				}
				showlegend = true
				legend = jsObject {
					orientation = "h"
				}
				margin = jsObject {
					l = 30
					r = 10
					b = 10
					t = 10
				}
			},
			config = jsObject {
				responsive = true
			}
		)
	}
}


class Micrograph1DPlot(
	val job: JobData,
	val micrograph: MicrographMetadata
) : CTF1DPlot(
	loader = {
		Services.jobs.getAvgRot(job.jobId, micrograph.id)
			.firstOrNull()
	},
	titler = {
		"Estimated Resolution ${micrograph.cccc.toFixed(2)} A"
	}
)


class TiltSeries1DPlot(
	val job: JobData,
	val tiltSeries: TiltSeriesData
) : CTF1DPlot(
	loader = {
		Services.jobs.getAvgRot(job.jobId, tiltSeries.id)
			.firstOrNull()
	},
	titler = {
		"Estimated Resolution ${tiltSeries.cccc.toFixed(2)} A"
	}
)


class SessionMicrograph1DPlot(
	session: SessionData,
	val micrograph: MicrographMetadata
) : CTF1DPlot(
	loader = {
		Services.sessions.getAvgRot(session.sessionId, micrograph.id)
			.firstOrNull()
	},
	titler = {
		"Estimated Resolution ${micrograph.cccc.toFixed(2)} A"
	}
)


class SessionTiltSeries1DPlot(
	session: SessionData,
	val tiltSeries: TiltSeriesData
) : CTF1DPlot(
	loader = {
		Services.sessions.getAvgRot(session.sessionId, tiltSeries.id)
			.firstOrNull()
	},
	titler = {
		"Estimated Resolution ${tiltSeries.cccc.toFixed(2)} A"
	}
)
