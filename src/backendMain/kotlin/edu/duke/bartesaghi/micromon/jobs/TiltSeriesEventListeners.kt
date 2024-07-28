package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.pyp.ArgValues
import edu.duke.bartesaghi.micromon.pyp.TiltSeries
import org.slf4j.LoggerFactory


/**
 * A mechanism to forward tilt series events to websocket clients listening for real-time updates.
 */
class TiltSeriesEventListeners(jobInfo: JobInfo) {


	private val log = LoggerFactory.getLogger("TiltSeriesEventListeners[${jobInfo.config.id}]")

	inner class Listener(val jobId: String) : AutoCloseable {

		var onTiltSeries: (suspend (TiltSeries) -> Unit)? = null
		var onParams: (suspend (ArgValues) -> Unit)? = null

		override fun close() {
			listenersByJob[jobId]?.remove(this)
		}
	}

	private val listenersByJob = HashMap<String,MutableList<Listener>>()

	fun add(jobId: String) =
		Listener(jobId).also {
			listenersByJob.getOrPut(jobId) { ArrayList() }.add(it)
		}

	suspend fun sendTiltSeries(jobId: String, tiltSeriesId: String) {
		val tiltSeries = TiltSeries.get(jobId, tiltSeriesId) ?: return
		listenersByJob[jobId]?.forEach { listener ->
			try {
				listener.onTiltSeries?.invoke(tiltSeries)
			} catch (ex: Throwable) {
				log.error("tilt series listener failed", ex)
			}
		}
	}

	suspend fun sendParams(jobId: String, values: ArgValues) {
		listenersByJob[jobId]?.forEach { listener ->
			try {
				listener.onParams?.invoke(values)
			} catch (ex: Throwable) {
				log.error("params listener failed", ex)
			}
		}
	}
}


interface TiltSeriesesJob {
	var latestTiltSeriesId: String?
	val eventListeners: TiltSeriesEventListeners
}
