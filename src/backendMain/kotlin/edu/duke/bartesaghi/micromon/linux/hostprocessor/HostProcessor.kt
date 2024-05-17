package edu.duke.bartesaghi.micromon.linux.hostprocessor

import edu.duke.bartesaghi.micromon.Config
import edu.duke.bartesaghi.micromon.use
import edu.duke.bartesaghi.micromon.SuspendCloseable
import edu.duke.bartesaghi.micromon.linux.Command
import edu.duke.bartesaghi.micromon.linux.recvFramedOrNull
import edu.duke.bartesaghi.micromon.linux.sendFramed
import edu.duke.bartesaghi.micromon.slowIOs
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.BufferUnderflowException
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.div


private val log = LoggerFactory.getLogger("HostProcessor")


class HostProcessor(
	val socketDir: Path = Config.instance.web.localDir / "host-processor",
	val pid: Long? = getPidFromEnv()
) : SuspendCloseable {

	companion object {

		fun getPidFromEnv(): Long? {

			// get the pid of the host processor from the environment
			val pidstr: String = System.getenv("NEXTPYP_HOSTPROCESSOR_PID")
				?: run {
					log.warn("no NEXTPYP_HOSTPROCESSOR_PID set, host processor will be unavailable")
					return null
				}
			return pidstr.toLongOrNull()
				?: run {
					log.warn("NEXTPYP_HOSTPROCESSOR_PID was not an integer (${pidstr}), host processor will be unavailable")
					return null
				}
		}
	}

	private val socketPath: Path? = pid
		?.let { socketDir / "host-processor-$it" }
		?.also { log.info("Connecting to host processor at socket file: {}", it) }

	private val connection: Connection? =
		socketPath?.let { Connection(it) }

	private val connectionOrThrow: Connection =
		connection ?: throw UnavailableError()


	override suspend fun closeAll() {
		connection?.close()
	}


	private class Connection(socketPath: Path): SuspendCloseable {

		// TODO: use some kind of weak map to cleanup responders that have forgotten to close?
		private val respondersByRequestId = HashMap<UInt,ConnectionResponder>()
		private val respondersLock = Mutex()
		private val nextRequestId = AtomicLong(1)

		private suspend fun <R> responders(block: suspend (HashMap<UInt, ConnectionResponder>) -> R): R =
			respondersLock.withLock {
				block(respondersByRequestId)
			}

		// deal with the sockets only on tasks confined to the IO thread pool
		private val scopeSocket = CoroutineScope(Dispatchers.IO)

		private val socket = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
			configureBlocking(true) // should be blocking by default, but let's be explicit
			connect(UnixDomainSocketAddress.of(socketPath))
		}

		// make a task to handle sending messages
		private val requestChannel = Channel<RequestEnvelope>(Channel.UNLIMITED)
		private val sender = scopeSocket.launch {

			// wait for requests from the channel and forward them to the socket
			for (request in requestChannel) {
				try {
					socket.sendFramed(request.encode())
				} catch (t: Throwable) {
					log.error("Failed to send request", t)
				}
			}

			// task exiting: close request channel so senders get an error instead of just buffering the traffic
			requestChannel.close()
		}

		// make a task to handle receiving messages
		private val receiver = scopeSocket.launch {

			// listen for responses from the socket
			while (true) {
				try {

					// wait for the next response, if any
					val response = socket.recvFramedOrNull()
						?.let { ResponseEnvelope.decode(it) }
						?: break

					// dispatch to a responder
					val responder = responders { it[response.id] }
					responder?.channel?.send(response.response)

				} catch (ex: BufferUnderflowException) {
					// error in decoding response, host processor and website got out of sync somehow
					// probably not recoverable, so just abort listener task
					log.error("Protocol Desync!", ex)
					break
				} catch (ex: ClosedSendChannelException) {
					// responder channel closed, safe to ignore
				} catch (t: Throwable) {
					log.error("Failed to receive response from socket", t)
				}
			}
		}

		override suspend fun closeAll() {
			requestChannel.close()
			slowIOs {
				socket.close()
			}
			sender.join()
			receiver.join()
		}

		private fun makeRequestId(): UInt =
			nextRequestId.getAndUpdate { i ->
				// just in case, roll over to 1 if we overflow a u32
				if (i >= UInt.MAX_VALUE.toLong()) {
					1
				} else {
					// otherwise, just increment like normal
					i + 1
				}
			}.toUInt()

		suspend fun send(request: Request) {
			val requestId = makeRequestId()
			requestChannel.send(RequestEnvelope(requestId, request))
		}

		suspend fun request(request: Request): Responder {

			val requestId = makeRequestId()

			// register a new responder
			val responder = ConnectionResponder(requestId)
			responders { it[requestId] = responder }

			requestChannel.send(RequestEnvelope(requestId, request))

			return responder
		}

		// NOTE: need an interface here to keep things private to the outer class, like properties, constructor
		interface Responder : SuspendCloseable {
			suspend fun recvResponse(): Response
			suspend fun send(request: Request)
		}

		private inner class ConnectionResponder(private val requestId: UInt) : Responder {

			val channel = Channel<Response>(Channel.UNLIMITED)

			override suspend fun recvResponse(): Response =
				channel.receive()

			override suspend fun send(request: Request) =
				requestChannel.send(RequestEnvelope(
					requestId,
					request
				))

			override suspend fun closeAll() {
				responders { it.remove(requestId) }
			}
		}
	}


	private suspend inline fun <reified T:Response> Connection.Responder.recv(): T {
		return when (val response = recvResponse()) {
			is T -> response // ok
			else -> throw UnexpectedResponseException(response)
		}
	}


	suspend fun ping() {
		connectionOrThrow
			.request(Request.Ping())
			.use { responder ->
				responder.recv<Response.Pong>()
			}
	}


	/**
	 * Launch a process
	 */
	suspend fun exec(cmd: Command, dir: Path? = null): Process =
		connectionOrThrow
			.request(
				Request.Exec(
					cmd.program,
					cmd.args,
					dir?.toString(),
					streamStdin = false,
					streamStdout = false,
					streamStderr = false,
					streamFin = false
				)
			)
			.use { responder ->
				val response = responder.recv<Response.Exec>()
				when (val r = response.response) {
					is Response.Exec.Success -> ProcessImpl(r.pid)
					is Response.Exec.Failure -> throw Error("Failed to launch process: ${r.reason}")
				}
			}

	// NOTE: need an interface here to keep things private to the outer class, like the constructor
	interface Process {
		val pid: UInt
		suspend fun status(): Boolean
		suspend fun kill()
	}

	open inner class ProcessImpl(override val pid: UInt) : Process {

		override suspend fun status(): Boolean =
			connectionOrThrow
				.request(Request.Status(pid))
				.use { responder ->
					responder.recv<Response.Status>()
						.isRunning
				}

		override suspend fun kill() =
			connectionOrThrow
				.send(Request.Kill(pid))
	}


	/**
	 * Launch a process and stream to and/or from it
	 */
	suspend fun execStream(
		cmd: Command,
		dir: Path? = null,
		stdin: Boolean = false,
		stdout: Boolean = false,
		stderr: Boolean = false
	): StreamingProcess {

		val responder = connectionOrThrow
			.request(
				Request.Exec(
					cmd.program,
					cmd.args,
					dir?.toString(),
					stdin,
					stdout,
					stderr,
					streamFin = true
				)
			)

		// handle the launch response
		when (val response = responder.recv<Response.Exec>().response) {
			is Response.Exec.Success -> return StreamingProcessImpl(response.pid, responder)
			is Response.Exec.Failure -> throw Error("Failed to launch process: ${response.reason}")
			else -> throw Error("Unexpected response: $response")
		}
	}

	// NOTE: need an interface here to keep things private to the outer class, like the constructor
	interface StreamingProcess : Process, SuspendCloseable {

		suspend fun recvEvent(): Response.ProcessEvent.Event

		interface Stdin {
			suspend fun write(bytes: ByteArray)
			suspend fun close()
		}
		val stdin: Stdin

		data class ProcessRun(
			val exitCode: Int?,
			val console: String
		)

		/**
		 * Wait for the process to finish and return its exit code and a combined stdout/stderr
		 */
		suspend fun run(): ProcessRun {

			val console = StringBuilder()

			while (true) {
				when (val event = recvEvent()) {

					is Response.ProcessEvent.Console ->
						console.append(event.chunk.toString(Charsets.UTF_8))

					is Response.ProcessEvent.Fin ->
						return ProcessRun(
							event.exitCode,
							console.toString()
						)
				}
			}
		}

		/**
		 * Disconnects from the running process, but does not terminate it.
		 * To ask the process to stop, call kill().
 		 */
		override suspend fun close() =
			super.close()
	}

	private inner class StreamingProcessImpl(
		pid: UInt,
		private val responder: Connection.Responder
	) : ProcessImpl(pid), StreamingProcess {

		override suspend fun closeAll() {
			responder.close()
		}

		override suspend fun recvEvent(): Response.ProcessEvent.Event =
			responder.recv<Response.ProcessEvent>().event

		override val stdin = object : StreamingProcess.Stdin {

			override suspend fun write(bytes: ByteArray) =
				responder.send(Request.WriteStdin(pid, bytes))

			override suspend fun close() {
				responder.send(Request.CloseStdin(pid))
			}
		}
	}

	suspend fun username(uid: UInt): String? =
		connectionOrThrow
			.request(Request.Username(uid))
			.use { responder ->
				responder.recv<Response.Username>()
					.username
			}

	suspend fun uid(username: String): UInt? =
		connectionOrThrow
			.request(Request.Uid(username))
			.use { responder ->
				responder.recv<Response.Uid>()
					.uid
			}

	suspend fun groupname(gid: UInt): String? =
		connectionOrThrow
			.request(Request.Groupname(gid))
			.use { responder ->
				responder.recv<Response.Groupname>()
					.groupname
			}

	suspend fun gid(groupname: String): UInt? =
		connectionOrThrow
			.request(Request.Gid(groupname))
			.use { responder ->
				responder.recv<Response.Gid>()
					.gid
			}

	suspend fun gids(uid: UInt): List<UInt>? =
		connectionOrThrow
			.request(Request.Gids(uid))
			.use { responder ->
				responder.recv<Response.Gids>()
					.gids
			}
}


suspend inline fun <reified T:Response.ProcessEvent.Event> HostProcessor.StreamingProcess.recv(): T =
	when (val event = recvEvent()) {
		is T -> event
		else -> throw Error("Unexpected event: $event")
	}


class UnexpectedResponseException(val response: Response) : RuntimeException("Unexpected response: $response")

class UnavailableError : IllegalStateException("The host processor is unavailable, try rebooting the website")
