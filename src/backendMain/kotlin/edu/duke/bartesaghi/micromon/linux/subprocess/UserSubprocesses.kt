package edu.duke.bartesaghi.micromon.linux.subprocess

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


class UserSubprocesses {

	private val subprocessesByUsername = HashMap<String,SubprocessClient>()
	private val mutex = Mutex()

	suspend fun get(username: String): SubprocessClient {

		mutex.withLock {

			// if a subprocess is already running, return that
			subprocessesByUsername[username]
				?. let { return it }

			// nope, start a new one
			// TODO: needs to use runas and start in a container
			throw Error() // TEMP
			val subprocess = SubprocessClient.start(username, 128, 10_000)
		}
	}
}
