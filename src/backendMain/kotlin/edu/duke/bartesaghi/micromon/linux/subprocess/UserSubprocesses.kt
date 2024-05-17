package edu.duke.bartesaghi.micromon.linux.subprocess

import edu.duke.bartesaghi.micromon.SuspendCloseable
import edu.duke.bartesaghi.micromon.linux.Runas
import edu.duke.bartesaghi.micromon.linux.hostprocessor.HostProcessor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory


class UserSubprocesses(val hostProcessor: HostProcessor) : SuspendCloseable {

	private val log = LoggerFactory.getLogger("UserSubprocesses")

	private val subprocessesByUsername = HashMap<String,SubprocessClient>()
	private val mutex = Mutex()

	private suspend fun <R> withSubprocesses(block: suspend (HashMap<String,SubprocessClient>) -> R): R =
		mutex.withLock {
			return block(subprocessesByUsername)
		}

	suspend fun get(username: String): SubprocessClient =
		withSubprocesses f@{ subprocesses ->

			// if a subprocess is already running, return that
			subprocesses[username]
				?. let { return@f it }

			// nope, start a new one
			log.debug("starting new subprocess for user: {}", username)
			val runas = Runas.findOrThrow(username, hostProcessor)
			val subprocess = SubprocessClient.start(username, 128, 10_000, hostProcessor, runas)
			subprocessesByUsername[username] = subprocess

			subprocess
		}

	override suspend fun closeAll() {

		log.debug("Closing...")

		// close all the clients
		withSubprocesses { subprocesses ->
			for (subprocess in subprocesses.values) {
				log.debug("Closing subprocess: {} ...", subprocess.name)
				subprocess.close()
			}
		}
	}
}
