package edu.duke.bartesaghi.micromon.sessions

import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.pyp.ArgValues
import edu.duke.bartesaghi.micromon.pyp.TwoDClasses
import edu.duke.bartesaghi.micromon.services.ClusterJobResultType
import edu.duke.bartesaghi.micromon.services.SessionDaemon


/**
 * Collects and translates session events from various sources, like ClusterJob and PypService
 */
class SessionEvents {

	inner class Listener(val sessionId: String) : AutoCloseable {

		var onDaemonSubmitted: (suspend (String, SessionDaemon) -> Unit)? = null
		var onDaemonStarted: (suspend (String, SessionDaemon) -> Unit)? = null
		var onDaemonFinished: (suspend (String, SessionDaemon) -> Unit)? = null

		var onJobSubmitted: (suspend (String, String, Int) -> Unit)? = null
		var onJobStarted: (suspend (String) -> Unit)? = null
		var onJobFinished: (suspend (String, ClusterJobResultType) -> Unit)? = null

		var onMicrograph: (suspend (String) -> Unit)? = null
		var onTwoDClasses: (suspend (TwoDClasses) -> Unit)? = null
		var onTiltSeries: (suspend (String) -> Unit)? = null
		var onParams: (suspend (ArgValues) -> Unit)? = null
		var onExport: (suspend (SessionExport) -> Unit)? = null

		override fun close() {
			synchronized(this@SessionEvents) {
				listeners[sessionId]?.remove(this)
			}
		}
	}

	val activeSessionIds = HashSet<String>()
	private val listeners = HashMap<String,MutableList<Listener>>()


	init {
		ClusterJob.addListener(object : ClusterJob.Listener {

			override suspend fun onSubmit(clusterJob: ClusterJob) {
				if (clusterJob.ownerId !in activeSessionIds) return

				// get the daemon, if any
				val daemon = SessionDaemon.getByClusterJobClusterName(clusterJob.clusterName)

				// forward message to clients if needed
				if (daemon != null) {
					listeners[clusterJob.ownerId]?.forEach { it.onDaemonSubmitted?.invoke(clusterJob.idOrThrow, daemon) }
				} else if (clusterJob.webName != null) {
					listeners[clusterJob.ownerId]?.forEach { it.onJobSubmitted?.invoke(clusterJob.idOrThrow, clusterJob.webName, clusterJob.commands.numJobs) }
				}
			}

			override suspend fun onStart(ownerId: String?, dbid: String) {
				if (ownerId !in activeSessionIds) return

				// get the daemon, if any
				val clusterJob = ClusterJob.get(dbid) ?: return
				val daemon = SessionDaemon.getByClusterJobClusterName(clusterJob.clusterName)

				// forward message to clients if needed
				if (daemon != null) {
					listeners[ownerId]?.forEach { it.onDaemonStarted?.invoke(dbid, daemon) }
				} else {
					listeners[ownerId]?.forEach { it.onJobStarted?.invoke(dbid) }
				}
			}

			override suspend fun onEnd(ownerId: String?, dbid: String, resultType: ClusterJobResultType) {
				if (ownerId !in activeSessionIds) return

				// get the daemon, if any
				val clusterJob = ClusterJob.get(dbid) ?: return
				val daemon = SessionDaemon.getByClusterJobClusterName(clusterJob.clusterName)

				// forward message to clients if needed
				if (daemon != null) {
					listeners[ownerId]?.forEach { it.onDaemonFinished?.invoke(dbid, daemon) }
				} else {
					listeners[ownerId]?.forEach { it.onJobFinished?.invoke(dbid, resultType) }
				}
			}
		})
	}

	fun sessionStarted(sessionId: String) {
		synchronized(this) {
			activeSessionIds.add(sessionId)
		}
	}

	fun sessionFinished(sessionId: String) {
		synchronized(this) {
			activeSessionIds.remove(sessionId)
		}
	}

	fun addListener(sessionId: String): Listener {
		val listener = Listener(sessionId)
		synchronized(this) {
			listeners.getOrPut(sessionId) { ArrayList() }.add(listener)
		}
		return listener
	}

	fun getListeners(sessionId: String): List<Listener> =
		synchronized(this) {
			listeners[sessionId] ?: emptyList()
		}
}
