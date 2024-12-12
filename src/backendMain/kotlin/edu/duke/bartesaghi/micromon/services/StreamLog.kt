package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.mongo.Database


object StreamLog {

	suspend fun add(clusterJobId: String, msg: StreamLogMsg) {

		// update the database
		Database.instance.pypLog.add(clusterJobId) {
			set("timestamp", msg.timestamp)
			set("level", msg.level)
			set("path", msg.path)
			set("line", msg.line)
			set("msg", msg.msg)
		}

		// fire events
		for (listener in listeners[clusterJobId] ?: emptyList()) {
			listener.onMsg?.invoke(msg)
		}
	}

	suspend fun end(clusterJobId: String, result: ClusterJob.Result) {

		// fire events
		for (listener in listeners[clusterJobId] ?: emptyList()) {
			listener.onEnd?.invoke(result)
		}
	}

	fun getAll(clusterJobId: String): List<StreamLogMsg> {

		val out = ArrayList<StreamLogMsg>()

		Database.instance.pypLog.getAll(clusterJobId) { cursor ->
			for (doc in cursor) {
				out.add(StreamLogMsg(
					timestamp = doc.getLong("timestamp"),
					level = doc.getInteger("level"),
					path = doc.getString("path"),
					line = doc.getInteger("line"),
					msg = doc.getString("msg")
				))
			}
		}

		return out
	}


	private val listeners = HashMap<String,MutableList<Listener>>()

	class Listener(val clusterJobId: String) : AutoCloseable {

		var onMsg: (suspend (StreamLogMsg) -> Unit)? = null
		var onEnd: (suspend (ClusterJob.Result) -> Unit)? = null

		override fun close() {
			listeners[clusterJobId]?.remove(this)
		}
	}

	fun addListener(clusterJobId: String): Listener =
		Listener(clusterJobId).also {
			listeners.getOrPut(clusterJobId) { ArrayList() }.add(it)
		}
}
