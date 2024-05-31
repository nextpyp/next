package edu.duke.bartesaghi.micromon.linux.userprocessor

import edu.duke.bartesaghi.micromon.SuspendCloseable
import edu.duke.bartesaghi.micromon.linux.hostprocessor.HostProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory


class UserProcessors(val hostProcessor: HostProcessor) : SuspendCloseable {

	private val log = LoggerFactory.getLogger("UserProcessors")

	private val processorsByUsername = HashMap<String,UserProcessor>()
	private val mutex = Mutex()
	private val watchersScope = CoroutineScope(Dispatchers.Default)

	private suspend fun <R> withProcessors(block: suspend (HashMap<String,UserProcessor>) -> R): R =
		mutex.withLock {
			return block(processorsByUsername)
		}

	suspend fun get(username: String): UserProcessor =
		withProcessors f@{ processors ->

			// if a subprocess is already running, return that
			processors[username]
				?. let { return@f it }

			// otherwise, start a new user processor
			log.debug("starting new subprocess for user: {}", username)
			val tracingLog =
				if (System.getenv("NEXTPYP_LOGS") == "dev") {
					"user_processor=debug"
				} else {
					null
				}
			val processor = UserProcessor.start(hostProcessor, username, 10_000, tracingLog)
			processorsByUsername[username] = processor

			// NOTE: JVM shutdown hooks apparently can't run suspend functions (via runBlocking()),
			//       so we can't cleanly shutdown the user processors here by calling close().
			//       Instead, we'll need to clean them up in the nextpyp stop script

			// set up a task to clear the processor if it stops working
			watchersScope.launch {
				while (true) {

					// wait a bit before trying
					delay(5000)

					try {
						withTimeout(5_000) {
							processor.ping()
						}
					} catch (t: Throwable) {

						log.error("Processor for \"{}\" failed", username, t)

						// processor failed, remove it
						// so the next time it can be restarted
						withProcessors { processors ->
							processors.remove(username)
						}

						// try to clean up the client side
						processor.close()

						break
					}
				}
			}

			processor
		}

	// NOTE: this is only called in test code
	override suspend fun closeAll() {

		log.debug("Closing...")

		// stop all the watcher tasks
		watchersScope.cancel()

		// grab all the running clients
		val running = withProcessors { processors ->
			processors.values.toList()
				.also { processors.clear() }
		}

		// close them all
		for (processor in running) {
			log.debug("Closing user processor: {} ...", processor.username)
			processor.close()
		}
	}
}
