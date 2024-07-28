package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.pyp.ArgValues
import edu.duke.bartesaghi.micromon.pyp.Micrograph
import org.slf4j.LoggerFactory


/**
 * A mechanism to forward micrograph events to websocket clients listening for real-time updates.
 */
class MicrographEventListeners(jobInfo: JobInfo) {

	private val log = LoggerFactory.getLogger("MicrographEventListeners[${jobInfo.config.id}]")

	inner class Listener(val jobId: String) : AutoCloseable {

		var onMicrograph: (suspend (Micrograph) -> Unit)? = null
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

	suspend fun sendMicrograph(jobId: String, micrographId: String) {
		val micrograph = Micrograph.get(jobId, micrographId) ?: return
		listenersByJob[jobId]?.forEach { listener ->
			try {
				listener.onMicrograph?.invoke(micrograph)
			} catch (ex: Throwable) {
				log.error("micrograph listener failed", ex)
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


interface MicrographsJob {
	var latestMicrographId: String?
	val eventListeners: MicrographEventListeners
}
