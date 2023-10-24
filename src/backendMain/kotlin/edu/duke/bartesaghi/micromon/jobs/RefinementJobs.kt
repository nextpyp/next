package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.pyp.ArgValues
import edu.duke.bartesaghi.micromon.pyp.Reconstruction
import edu.duke.bartesaghi.micromon.pyp.Refinement
import edu.duke.bartesaghi.micromon.pyp.RefinementBundle
import org.slf4j.LoggerFactory


/**
 * The place to put things common to all refinement-type jobs.
 *
 * Like things that are shared at runtime between all refinement-type jobs (like events),
 * or code used by all refinement-type jobs
 */
object RefinementJobs {

	class EventListeners {

		private val log = LoggerFactory.getLogger("IntegratedRefinement")

		inner class Listener(val jobId: String) : AutoCloseable {

			var onParams: (suspend (ArgValues) -> Unit)? = null
			var onReconstruction: (suspend (Reconstruction) -> Unit)? = null
			var onRefinement: (suspend (Refinement) -> Unit)? = null
			var onRefinementBundle: (suspend (RefinementBundle) -> Unit)? = null

			override fun close() {
				listenersByJob[jobId]?.remove(this)
			}
		}

		private val listenersByJob = HashMap<String,MutableList<Listener>>()

		fun add(jobId: String) =
			Listener(jobId).also {
				listenersByJob.getOrPut(jobId) { ArrayList() }.add(it)
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

		suspend fun sendReconstruction(jobId: String, reconstructionId: String) {
			val micrograph = Reconstruction.get(jobId, reconstructionId) ?: return
			listenersByJob[jobId]?.forEach { listener ->
				try {
					listener.onReconstruction?.invoke(micrograph)
				} catch (ex: Throwable) {
					log.error("micrograph listener failed", ex)
				}
			}
		}

		suspend fun sendRefinement(jobId: String, dataId: String) {
			val refinement = Refinement.get(jobId, dataId) ?: return
			listenersByJob[jobId]?.forEach { listener ->
				try {
					listener.onRefinement?.invoke(refinement)
				} catch (ex: Throwable) {
					log.error("refinement listener failed", ex)
				}
			}
		}

		suspend fun sendRefinementBundle(jobId: String, refinementBundle: RefinementBundle) {
			listenersByJob[jobId]?.forEach { listener ->
				try {
					listener.onRefinementBundle?.invoke(refinementBundle)
				} catch (ex: Throwable) {
					log.error("refinement bundle listener failed", ex)
				}
			}
		}
	}

	val eventListeners = EventListeners()
}
