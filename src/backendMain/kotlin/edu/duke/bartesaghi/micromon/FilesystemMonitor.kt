package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.linux.DF
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.delay
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration


typealias FilesystemListener = suspend (filesystems: List<DF.Filesystem>) -> Unit


/**
 * Continually scans the filesytem and keeps
 * up-to-date information on usage and free space.
 */
class FilesystemMonitor(val configs: List<Config.Web.Filesystem>, val email: Email) {

	private val log = LoggerFactory.getLogger(javaClass)

	private var _filesystems: List<DF.Filesystem>? = null

	/**
	 * The most recent information on local filesystems, updated periodically by a background thread.
	 * List instances returned here are immutable and will not be changed by the background thread.
	 */
	suspend fun filesystems(): List<DF.Filesystem> {

		if (_filesystems == null) {
			update()
		}

		return _filesystems ?: emptyList()
	}


	inner class Listeners {

		private val listeners = HashMap<Long,FilesystemListener>()

		fun add(listener: FilesystemListener): Long {
			val id = listeners.uniqueKey()
			listeners[id] = listener
			return id
		}

		fun remove(id: Long) {
			listeners.remove(id)
		}

		suspend fun dispatch() {
			for (listener in listeners.values) {
				listener(filesystems())
			}
		}
	}
	val listeners = Listeners()

	companion object {
		const val alertTypeId = "filesystem_low_space"
		const val NsPerHr = 60L * 60L * 1_000_000_000L
	}

	private val thread = Thread {
		runBlocking {

			// just update every 30 seconds forever
			while (true) {
				update()
				delay(30.seconds.toJavaDuration())
			}
		}

	}.apply {
		name = "FilesystemMonitor"
		isDaemon = true
		start()
	}

	private suspend fun update() {

		// get new info about filesystems
		// NOTE: make sure to replace the old instance of the list and keep the lists immutable to avoid concurrency issues
		_filesystems = DF.run()

		// update listeners, if any
		listeners.dispatch()

		/* TODO: process alerts

		// send any filesystem alerts, if needed
		for (fs in configs) {

			if (fs == null) {
				continue
			}

			// is the alert condition met?
			val alertConfig = cfs.lowSpaceAlert ?: continue
			if (fs.gibFree >= alertConfig.GiB) {
				continue
			}

			// yup, did we already alert recently?
			val alertDb = Database.alerts.get(alertTypeId, cfs.name)
			val elapsedHrs = (System.nanoTime() - (alertDb.lastNotificationNs ?: 0))/NsPerHr
			if (elapsedHrs <= alertConfig.intervalHrs) {
				continue
			}

			// nope, send an alert now
			email.send(
				to = alertConfig.emailAddress,
				subject = "Low space alert on filesystem: ${cfs.name}",
				body = """
					|Filesystem ${cfs.name} has ${fs.gibFree} GiB free,
					|which is less than the alert threshold of ${alertConfig.GiB} GiB.
				""".trimMargin()
			)

			// and log the alert too
			log.info("Filesystem ${cfs.name} has ${fs.gibFree} (< ${alertConfig.GiB}) GiB free, sent email to ${alertConfig.emailAddress}")

			// update the database
			alertDb.lastNotificationNs = System.nanoTime()
			Database.alerts.put(alertDb)
		}
		*/
	}
}