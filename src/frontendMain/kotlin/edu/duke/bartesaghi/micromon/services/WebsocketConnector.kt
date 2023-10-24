package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.reportError
import edu.duke.bartesaghi.micromon.sendMessage
import io.kvision.remote.*
import kotlinx.browser.window
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.selects.SelectClause2
import kotlin.js.Date


/**
 * Manages websocket connections.
 *
 * Allows:
 *   connecting to servers
 *   disconnecting connections
 *   recovering gracefully from unexpected disconnections
 *   re-connecting after disconnections
 *
 * Use the companion function new() to create one.
 */
class WebsocketConnector(
	val path: String,
	val block: suspend (Signaler, ReceiveChannel<String>, SendChannel<String>) -> Unit,
) {

	/**
	 * Used to signal connctioon events during message processing
	 */
	interface Signaler {
		/**
		 * Call after the initlial setup message processing is complete,
		 * but before moving on to the passive listenening phase of the connection.
		 */
		fun connected()
	}

	enum class State {
		Connecting,
		Connected,
		Disconnecting,
		Disconnected
	}

	var state: State = State.Disconnected
		private set

	var onStateChange: (State, Throwable?) -> Unit = { _, _ -> }

	private var disconnector: (() -> Unit)? = null
	private var connectingJob: Job? = null
	private var keeper: KeeperOfTheAlive? = null

	fun connect() {

		if (state != State.Disconnected) {
			return
		}
		changeState(State.Connecting, null)

		// build the websocket url from the path
		val url = run {
			val proto = when (window.location.protocol) {
				"https:" -> "wss"
				else -> "ws"
			}
			val port = when (window.location.port) {
				"" -> ""
				else -> ":${window.location.port}"
			}
			val path = if (path.startsWith("/")) {
				path.substring(1)
			} else {
				throw Error("websocket paths should be absolute, not: $path")
			}
			"$proto://${window.location.hostname}$port/$path"
		}

		// launch the connection coroutine
		// but keep track of the job so we can cancel it
		connectingJob = AppScope.launch {

			// open the websocket connection
			val socket = Socket()
			val output = Channel<String>()
			val input = Channel<String>()
			try {
				socket.connect(url)
			} catch (d: dynamic) {
				throw Error("can't connect to $path", d)
			}

			var outputJob: Job? = null
			var inputJob: Job? = null

			fun cleanup() {
				outputJob?.cancel()
				inputJob?.cancel()
				if (!output.isClosedForSend) {
					output.close()
				}
				if (!input.isClosedForReceive) {
					input.close()
				}
			}

			// route outgoing messages from the output channel to the websocket connnection
			outputJob = AppScope.launch {
				for (str in output) {
					try {
						socket.send(str)
					} catch (ex: SocketClosedException) {
						break
					}
				}
				cleanup()
			}

			// route incoming messages from the websocket connection to the input channel
			inputJob = AppScope.launch {
				while (true) {
					try {
						val str = socket.receive()
						input.send(str)
					} catch (ex: SocketClosedException) {
						break
					}
				}
				cleanup()
			}

			// start the keep-alive function
			val keeper = KeeperOfTheAlive(output, input)
			this@WebsocketConnector.keeper = keeper

			// make the signaller for the caller to use
			val signaler = object : Signaler {

				override fun connected() {

					connectingJob = null
					disconnector = { output.close() }
					changeState(State.Connected)
				}
			}

			try {

				// the connection is ready, give it to the caller
				// but send the wrapped channels so we can monitor activity
				block(signaler, keeper.watchedInput, keeper.watchedOutput)

				// if we get here, the connection exited cleanly
				disconnector = null
				changeState(State.Disconnected)

			} catch (ex: CancellationException) {

				// KVision throws this exception to cancel the coroutine when the connection is closed
				// it's safe to ignore

				// but we're still disconnected
				disconnector = null
				changeState(State.Disconnected, null)

			} catch (t: Throwable) {

				// disconnected with an error
				disconnector = null
				changeState(State.Disconnected, t)
			}

			when (state) {

				// if we got here while still connecting, that means the connection attempt failed
				State.Connecting -> {
					changeState(State.Disconnected)
				}

				// someone explicitly tried to abort the connection attempt before it could finish
				State.Disconnecting -> {
					changeState(State.Disconnected)
				}

				// all is well
				State.Disconnected -> Unit

				else -> console.warn("unexpected connection state", state)
			}
		}
	}

	fun disconnect() {

		val oldState = state

		changeState(State.Disconnecting)

		when (oldState) {

			State.Connecting -> {
				// cancel the connecting job
				connectingJob?.cancel()
				connectingJob = null
			}

			State.Connected -> {
				// call the disconnector and hope it works
				disconnector?.invoke()
				disconnector = null
			}

			else -> Unit // nothing to do
		}
	}

	data class IdleReport(
		val openSeconds: Int,
		val idleSeconds: Int,
		val timeoutSeconds: Int
	)

	var finalIdleReport: IdleReport? = null
		private set

	fun idleReport(): IdleReport? =
		keeper?.idleReport()

	private fun changeState(new: State, t: Throwable? = null) {

		state = new

		// call the state change handler,
		// but don't let its exceptions break the websocket connection
		try {
			onStateChange(state, t)
		} catch (t: Throwable) {
			t.reportError()
		}
	}

	private inner class KeeperOfTheAlive(
		val output: SendChannel<String>,
		val input: ReceiveChannel<String>
	) {
		// wrap the send channel so we can observe when messages are sent
		val watchedOutput: SendChannel<String> = object : SendChannel<String> {

			@ExperimentalCoroutinesApi
			override val isClosedForSend: Boolean
				get() = output.isClosedForSend
			override val onSend: SelectClause2<String, SendChannel<String>>
				get() = output.onSend

			override fun close(cause: Throwable?): Boolean =
				output.close(cause)

			@ExperimentalCoroutinesApi
			override fun invokeOnClose(handler: (cause: Throwable?) -> Unit) =
				output.invokeOnClose(handler)

			override suspend fun send(element: String) {
				output.send(element)
				updateActivity()
			}

			override fun trySend(element: String): ChannelResult<Unit> =
				output.trySend(element)
					.onSuccess {
						updateActivity()
					}
		}

		// do the same wrapping for the input stream, but filter out the Pong messages
		val watchedInput: ReceiveChannel<String> = object : ReceiveChannel<String> {

			@ExperimentalCoroutinesApi
			override val isClosedForReceive: Boolean
				get() = input.isClosedForReceive

			@ExperimentalCoroutinesApi
			override val isEmpty: Boolean
				get() = input.isEmpty
			override val onReceive: SelectClause1<String>
				get() = input.onReceive
			override val onReceiveCatching: SelectClause1<ChannelResult<String>>
				get() = input.onReceiveCatching

			@Suppress("OverridingDeprecatedMember")
			override fun cancel(cause: Throwable?): Boolean =
				when (cause) {
					is CancellationException -> {
						cancel(cause)
						true
					}
					else -> false
				}

			override fun cancel(cause: CancellationException?) =
				input.cancel(cause)

			override fun iterator(): ChannelIterator<String> = object : ChannelIterator<String> {

				private val iter = input.iterator()
				private var next: String? = null

				override suspend fun hasNext(): Boolean {
					while (true) {
						val hasNext = iter.hasNext()
						if (!hasNext) {
							return false
						}
						val next = iter.next()
						if (!next.isPong()) {
							this.next = next
							updateActivity()
							return true
						}
					}
				}

				override fun next(): String {
					val out = next ?: throw NoSuchElementException()
					next = null
					return out
				}
			}

			override suspend fun receive(): String {
				while (true) {
					val msg = input.receive()
					if (!msg.isPong()) {
						updateActivity()
						return msg
					}
				}
			}

			override suspend fun receiveCatching(): ChannelResult<String> {
				while (true) {
					val result = input.receiveCatching()
					result.onSuccess { msg ->
						if (!msg.isPong()) {
							updateActivity()
							return result
						}
					}.onFailure {
						return result
					}
				}
			}

			override fun tryReceive(): ChannelResult<String> {
				while (true) {
					val result = input.tryReceive()
					result.onSuccess { msg ->
						if (!msg.isPong()) {
							updateActivity()
							return result
						}
					}.onFailure {
						return result
					}
				}
			}
		}

		private fun String.isPong(): Boolean =
			// properly decoding the message is a rather expensive predicate to evaluate if messages get large
			//RealTimeS2C.fromJson(this) is RealTimeS2C.Pong
			// instead, since pong messages are so small, just match the raw string encoding
			equals("{\"type\":\"edu.duke.bartesaghi.micromon.services.RealTimeS2C.Pong\"}")

		private val firstActivity = Date.now()
		private var lastActivity = firstActivity

		private fun updateActivity() {
			lastActivity = Date.now()
		}

		private fun Double.secondsSince(): Int {
			val sinceMs = Date.now() - this
			return (sinceMs/1000.0).toInt()
		}

		fun openSeconds(): Int =
			firstActivity.secondsSince()

		fun idleSeconds(): Int =
			lastActivity.secondsSince()

		fun idleReport(): IdleReport =
			IdleReport(
				openSeconds(),
				idleSeconds(),
				timeoutSeconds
			)

		init {
			AppScope.launch {

				finalIdleReport = null

				// empirically, connections seem to timeout at around 30 seconds of inactivity
				// so send a ping request periodically
				// until we reach the desired long-term timeout
				try {
					@Suppress("OverridingDeprecatedMember")
					while (idleSeconds() < timeoutSeconds) {
						delay(intervalSeconds*1000L)
						output.sendMessage(RealTimeC2S.Ping())
					}

					// end of timeout, close the connection
					output.close()

				} catch (t: Throwable) {
					// something failed, that usually means the conection closed
					// so our work here is done
				} finally {
					finalIdleReport = idleReport()
				}
			}
		}
	}

	companion object {
		// empirically, connections seem to timeout at around 30 seconds of inactivity
		// so a ping every 25 seconds or so should keep things working
		const val intervalSeconds = 25
		const val timeoutSeconds = 1*60*60 // 1 hour
	}
}
