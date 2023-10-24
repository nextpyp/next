package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.*
import js.plotly.plot
import kotlinext.js.jsObject
import io.kvision.html.div
import io.kvision.html.span

open class MotionPlot(
	val loader: suspend () -> MotionData?,
	val titler: () -> String
) : SizedPanel("Motion Trajectory", Storage.motionSize, defaultToLarge = false) {


	private var motion: MotionData? = null
	private val container = div()

	init {

		addHeader()

		onResize = { newSize: ImageSize ->

			// save the selection in storage
			Storage.motionSize = newSize

			replot()
		}
	}

	fun loadData() {

		// gather the data to show the motion plot
		AppScope.launch {

			val loadingElem = loading("Fetching data ...")
			val motion: MotionData? = try {
				delayAtLeast(100) {
					loader()
				}
			} catch (t: Throwable) {
				errorMessage(t)
				null
			} finally {
				remove(loadingElem)
			}

			setData(motion)
		}
	}

	fun setData(motion: MotionData?) {
		this.motion = motion
		replot()
	}

	private fun addHeader() {
		// can't use implicit receivers inside of DSL blocks anymore =(
		val me = this
		container.div {
			span(me.titler())
		}
	}

	fun replot() {

		container.removeAll()
		addHeader()

		val motion = motion
		if (motion == null) {
			container.div("(No motion data)", classes = setOf("empty"))
			return
		}

		container.plot(
			jsObject {
				x = motion.x.toTypedArray()
				y = motion.y.toTypedArray()
				type = "scatter"
				mode = "lines+markers"
				marker = jsObject {
					color = IntArray(motion.x.size) { it }
				}
			},

			layout = jsObject {
				autosize = true
				height = size.approxWidth - 60
				showlegend = false
				margin = jsObject {
					l = 30
					r = 10
					b = 30
					t = 10
				}
				yaxis = jsObject {
					scaleanchor = "x"
				}
			},
			config = jsObject {
				responsive = true
			}
		)
	}
}


class MicrographMotionPlot(
	val job: JobData,
	val micrograph: MicrographMetadata
) : MotionPlot(
	loader = {
		Services.jobs.getMotion(job.jobId, micrograph.id)
			.firstOrNull()
	},
	titler = {
		"Average drift ${micrograph.averageMotion.toFixed(2)} pixels"
	}
)


class TiltSeriesMotionPlot(
	val job: JobData,
	val tiltSeries: TiltSeriesData
) : MotionPlot(
	loader = {
		Services.jobs.getMotion(job.jobId, tiltSeries.id)
			.firstOrNull()
	},
	titler = {
		"Average drift ${tiltSeries.averageMotion.toFixed(2)} pixels"
	}
)

class SessionMicrographMotionPlot(
	session: SessionData,
	val micrograph: MicrographMetadata
) : MotionPlot(
	loader = {
		Services.sessions.getMotion(session.sessionId, micrograph.id)
			.firstOrNull()
	},
	titler = {
		"Average drift ${micrograph.averageMotion.toFixed(2)} pixels"
	}
)

class SessionTiltSeriesMotionPlot(
	session: SessionData,
	val tiltSeries: TiltSeriesData
) : MotionPlot(
	loader = {
		Services.sessions.getMotion(session.sessionId, tiltSeries.id)
			.firstOrNull()
	},
	titler = {
		"Average drift ${tiltSeries.averageMotion.toFixed(2)} pixels"
	}
)
