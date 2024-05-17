package edu.duke.bartesaghi.micromon.linux.userprocessor

import edu.duke.bartesaghi.micromon.SuspendCloseable
import edu.duke.bartesaghi.micromon.linux.hostprocessor.HostProcessor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory


class UserProcessors(val hostProcessor: HostProcessor) : SuspendCloseable {

	private val log = LoggerFactory.getLogger("UserSubprocesses")

	private val processorsByUsername = HashMap<String,UserProcessor>()
	private val mutex = Mutex()

	private suspend fun <R> withProcessors(block: suspend (HashMap<String,UserProcessor>) -> R): R =
		mutex.withLock {
			return block(processorsByUsername)
		}

	suspend fun get(username: String, tracingLog: String? = null): UserProcessor =
		withProcessors f@{ processors ->

			// if a subprocess is already running, return that
			processors[username]
				?. let { return@f it }

			// nope, start a new one
			log.debug("starting new subprocess for user: {}", username)
			val processor = UserProcessor.start(hostProcessor, username, 10_000, tracingLog)
			processorsByUsername[username] = processor

			processor
		}

	override suspend fun closeAll() {

		log.debug("Closing...")

		// close all the clients
		withProcessors { processors ->
			for (processor in processors.values) {
				log.debug("Closing user processor: {} ...", processor.username)
				processor.close()
			}
		}
	}
}
