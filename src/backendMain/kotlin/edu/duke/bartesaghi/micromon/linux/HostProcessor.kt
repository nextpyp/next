
@file:Suppress("RemoveRedundantQualifierName")
// The linter thinks some of the Request.* qualifications are reduntant and some aren't.
// Same with Response.*
// The inconsistency is super annoying, so just allow all the qualifiers and stop complaining.

package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.SuspendCloseable
import edu.duke.bartesaghi.micromon.linux.HostProcessor.Connection.Responder
import edu.duke.bartesaghi.micromon.linux.Request.Exec
import edu.duke.bartesaghi.micromon.linux.Request.Ping
import edu.duke.bartesaghi.micromon.linux.Response.Pong
import edu.duke.bartesaghi.micromon.slowIOs
import edu.duke.bartesaghi.micromon.use
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.*
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.div


private val log = LoggerFactory.getLogger("HostProcessor")


class HostProcessor(
	val socketDir: Path,
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

	private val connection: Connection? =
		socketPath?.let { Connection(it) }

	private val connectionOrThrow: Connection =
		connection ?: throw UnavailableError()


	override suspend fun close() {
		connection?.close()
	}


	class Connection(socketPath: Path): SuspendCloseable {

		// TODO: use some kind of weak map to cleanup responders that have forgotten to close?
		private val respondersByRequestId = HashMap<UInt,Responder>()
		private val respondersLock = Mutex()
		private val nextRequestId = AtomicLong(1)

		private suspend fun <R> responders(block: suspend (HashMap<UInt,Responder>) -> R): R =
			respondersLock.withLock {
				block(respondersByRequestId)
			}

		// deal with the sockets only on tasks confined to the IO thread pool
		private val scope = CoroutineScope(Dispatchers.IO)

		private val socket = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
			connect(UnixDomainSocketAddress.of(socketPath))
		}

		// make a task to handle sending messages
		private val requestChannel = Channel<RequestEnvelope>()
		private val sender = scope.launch {
			for (request in requestChannel) {
				try {
					socket.send(request)
				} catch (t: Throwable) {
					log.error("Failed to send request", t)
				}
			}
		}

		// make a task to handle receiving messages
		private val responseChannel = Channel<Response>()
		private val receiver = scope.launch {
			while (true) {
				try {

					// wait for the next response
					val response = socket.recvResponse()

					// dispatch to a responder
					responders { it[response.id]?.channel?.send(response.response) }

				} catch (ex: AsynchronousCloseException) {
					// socket closed, exit the receiver task
					break
				} catch (t: Throwable) {
					log.error("Failed to handle response", t)
				}
			}
		}

		override suspend fun close() {
			responseChannel.close()
			requestChannel.close()
			slowIOs {
				socket.close()
			}
			sender.join()
			receiver.join()
		}

		suspend fun request(request: Request): Responder {

			// pick an id for the request and build the envelope
			val requestId = nextRequestId.getAndUpdate { i ->
				// just in case, roll over to 1 if we overflow a u32
				if (i >= UInt.MAX_VALUE.toLong()) {
					1
				} else {
					// otherwise, just increment like normal
					i + 1
				}
			}.toUInt()
			val envelope = RequestEnvelope(requestId, request)

			// register a new responder
			val responder = Responder(envelope.id)
			responders { it[requestId] = responder }

			// relay the request to the sender task
			requestChannel.send(envelope)

			return responder
		}

		inner class Responder(private val requestId: UInt) : SuspendCloseable {

			val channel = Channel<Response>()

			suspend inline fun <reified T:Response> recv(): T {
				val response = channel.receive()
				return when (response) {
					is T -> response // ok
					else -> throw UnexpectedResponseException(response)
				}
			}

			override suspend fun close() {
				responders { it.remove(requestId) }
			}
		}
	}


	suspend fun ping() {
		connectionOrThrow
			.request(Ping())
			.use { responder ->
				responder.recv<Pong>()
			}
	}


	/**
	 * Launch a process
	 */
	suspend fun exec(program: String, args: List<String>): Process =
		connectionOrThrow
			.request(Request.Exec(
				program,
				args,
				streamStdin = false,
				streamStdout = false,
				streamStderr = false,
				streamFin = false
			))
			.use { responder ->
				val response = responder.recv<Response.Exec>()
				when (val r = response.response) {
					is Response.Exec.Success -> Process(r.pid)
					is Response.Exec.Failure -> throw Error("Failed to launch process: ${r.reason}")
				}
			}

	open class Process(val pid: UInt) {
		// TODO: status, kill
	}


	/**
	 * Launch a process and stream to and/or from it
	 */
	suspend fun execStream(
		program: String,
		args: List<String>,
		stdin: Boolean = false,
		stdout: Boolean = false,
		stderr: Boolean = false
	): StreamingProcess {

		val responder = connectionOrThrow
			.request(Request.Exec(
				program,
				args,
				stdin,
				stdout,
				stderr,
				streamFin = true
			))

		// handle the launch response
		when (val response = responder.recv<Response.Exec>().response) {
			is Response.Exec.Success -> return StreamingProcess(response.pid, responder)
			is Response.Exec.Failure -> throw Error("Failed to launch process: ${response.reason}")
			else -> throw Error("Unexpected response: $response")
		}
	}

	class StreamingProcess(
		pid: UInt,
		val responder: Responder
	) : Process(pid), SuspendCloseable {

		override suspend fun close() {
			responder.close()
		}

		suspend inline fun <reified T:Response.ProcessEvent.Event> recv(): T =
			when (val event = responder.recv<Response.ProcessEvent>().event) {
				is T -> event
				else -> throw Error("Unexpected event: $event")
			}
	}

	// TODO: other commands
}


private fun SocketChannel.send(request: RequestEnvelope) {
	val encoded = request.encode()
	write(ByteBuffer.allocate(4).apply {
		putInt(encoded.size)
		flip()
	})
	write(ByteBuffer.wrap(encoded))
}

private fun SocketChannel.recvResponse(): ResponseEnvelope {

	// read the message size
	val sizeBuf = ByteBuffer.allocate(4)
	read(sizeBuf)
	sizeBuf.flip()
	val size = sizeBuf.getInt().toUInt().toIntOrThrow()

	// read the message
	val buf = ByteBuffer.allocate(size)
	read(buf)
	return ResponseEnvelope.decode(buf.array())
}


class UnexpectedResponseException(val response: Response) : RuntimeException("Unexpected response: $response")

class UnavailableError : IllegalStateException("The host processor is unavailable, try rebooting the website")


sealed interface Request {

	class Ping : Request {
		companion object {
			const val ID: UInt = 1u
		}
	}

	data class Exec(
		val program: String,
		val args: List<String>,
		val streamStdin: Boolean,
		val streamStdout: Boolean,
		val streamStderr: Boolean,
		val streamFin: Boolean
	) : Request {
		companion object {
			const val ID: UInt = 2u
		}
	}
}


private class RequestEnvelope(
	val id: UInt,
	val request: Request
) {
	fun encode(): ByteArray {

		val bos = ByteArrayOutputStream()
		val out = DataOutputStream(bos)

		out.writeU32(id)

		when (request) {

			is Request.Ping -> {
				// only the id needed here
				out.writeU32(Request.Ping.ID)
			}

			is Request.Exec -> {
				out.writeU32(Request.Exec.ID)
				out.writeUtf8(request.program)
				out.writeArray(request.args) {
					out.writeUtf8(it)
				}
				out.writeBoolean(request.streamStdin)
				out.writeBoolean(request.streamStdout)
				out.writeBoolean(request.streamStderr)
				out.writeBoolean(request.streamFin)
			}

			// TODO
		}

		return bos.toByteArray()
	}

	companion object {

		fun decode(msg: ByteArray): RequestEnvelope {

			val input = DataInputStream(ByteArrayInputStream(msg))

			val id = input.readU32()

			@Suppress("RemoveRedundantQualifierName")
			// The linter thinks some of these are reduntant and some aren't.
			// The inconsistency is super annoying, so just allow all the qualifiers and stop complaining.
			val request: Request = when (val typeId = input.readU32()) {

				Request.Ping.ID -> Ping()

				Request.Exec.ID -> Exec(
					program = input.readUtf8(),
					args = input.readArray {
						input.readUtf8()
					},
					streamStdin = input.readBoolean(),
					streamStdout = input.readBoolean(),
					streamStderr = input.readBoolean(),
					streamFin = input.readBoolean()
				)

				// TODO

				else -> throw NoSuchElementException("unrecognized response type id: $typeId")
			}

			return RequestEnvelope(id, request)
		}
	}
}


sealed interface Response {

	data class Error(val reason: String) : Response {
		companion object {
			const val ID: UInt = 1u
		}
	}

	class Pong : Response {
		companion object {
			const val ID: UInt = 2u
		}
	}

	data class Exec(val response: Response) : Response {
		companion object {
			const val ID: UInt = 3u
		}

		sealed interface Response

		data class Success(val pid: UInt) : Response {
			companion object {
				const val ID: UInt = 1u
			}
		}

		data class Failure(val reason: String) : Response {
			companion object {
				const val ID: UInt = 2u
			}
		}
	}

	data class ProcessEvent(val event: Event) : Response {
		companion object {
			const val ID: UInt = 4u
		}

		sealed interface Event

		data class Console(val kind: ConsoleKind, val chunk: ByteArray): Event {
			companion object {
				const val ID: UInt = 1u
			}
		}

		data class Fin(val exitCode: Int?) : Event {
			companion object {
				const val ID: UInt = 2u
			}
		}

		enum class ConsoleKind(val id: UInt) {

			Stdout(1u),
			Stderr(2u);

			companion object {

				operator fun get(id: UInt): ConsoleKind =
					when (id) {
						Stdout.id -> Stdout
						Stderr.id -> Stderr
						else -> throw NoSuchElementException("unrecognized console kind: $id")
					}
			}
		}
	}
}


private class ResponseEnvelope(
	val id: UInt,
	val response: Response
) {

	fun encode(): ByteArray {

		val bos = ByteArrayOutputStream()
		val out = DataOutputStream(bos)

		out.writeU32(id)

		@Suppress("RemoveRedundantQualifierName")
		// The linter thinks some of these are reduntant and some aren't.
		// The inconsistency is super annoying, so just allow all the qualifiers and stop complaining.
		when (response) {

			is Response.Error -> {
				out.writeU32(Response.Error.ID)
				out.writeUtf8(response.reason)
			}

			is Response.Pong -> {
				// only the id needed here
				out.writeU32(Response.Pong.ID)
			}

			is Response.Exec -> {
				out.writeU32(Response.Exec.ID)
				when (val response = response.response) {
					is Response.Exec.Success -> {
						out.writeU32(Response.Exec.Success.ID)
						out.writeU32(response.pid)
					}
					is Response.Exec.Failure -> {
						out.writeU32(Response.Exec.Failure.ID)
						out.writeUtf8(response.reason)
					}
				}
			}

			is Response.ProcessEvent -> {
				out.writeU32(Response.ProcessEvent.ID)
				when (val event = response.event) {
					is Response.ProcessEvent.Console -> {
						out.writeU32(Response.ProcessEvent.Console.ID)
						out.writeU32(event.kind.id)
						out.writeBytes(event.chunk)
					}
					is Response.ProcessEvent.Fin -> {
						out.writeU32(Response.ProcessEvent.Fin.ID)
						out.writeOption(event.exitCode) {
							out.writeInt(it)
						}
					}
				}
			}

			// TODO
		}

		return bos.toByteArray()
	}


	companion object {

		fun decode(msg: ByteArray): ResponseEnvelope {

			val input = DataInputStream(ByteArrayInputStream(msg))

			val id = input.readU32()

			@Suppress("RemoveRedundantQualifierName")
			// The linter thinks some of these are reduntant and some aren't.
			// The inconsistency is super annoying, so just allow all the qualifiers and stop complaining.
			val response: Response = when (val responseTypeId = input.readU32()) {

				Response.Error.ID -> Response.Error(
					reason = input.readUtf8()
				)

				Response.Pong.ID -> Pong()

				Response.Exec.ID -> Response.Exec(when (val execTypeId = input.readU32()) {
					Response.Exec.Success.ID -> Response.Exec.Success(
						pid = input.readU32()
					)
					Response.Exec.Failure.ID -> Response.Exec.Failure(
						reason = input.readUtf8()
					)
					else -> throw NoSuchElementException("unrecognized response exec type: $execTypeId")
				})

				Response.ProcessEvent.ID -> Response.ProcessEvent(when (val eventTypeId = input.readU32()) {
					Response.ProcessEvent.Console.ID -> Response.ProcessEvent.Console(
						kind = Response.ProcessEvent.ConsoleKind[input.readU32()],
						chunk = input.readBytes()
					)
					Response.ProcessEvent.Fin.ID -> Response.ProcessEvent.Fin(
						exitCode = input.readOption {
							input.readInt()
						}
					)
					else -> throw NoSuchElementException("unrecognized response process event type: $eventTypeId")
				})

				// TODO

				else -> throw NoSuchElementException("unrecognized response type: $responseTypeId")
			}

			return ResponseEnvelope(id, response)
		}
	}
}


private fun UInt.toIntOrThrow(): Int {
	val i = toInt()
	if (i < 0) {
		throw IllegalArgumentException("Too large to fit in signed int: $this")
	}
	return i
}


private fun DataOutput.writeU32(i: UInt) =
	writeInt(i.toInt())

private fun DataInput.readU32(): UInt =
	readInt().toUInt()


private fun DataOutput.writeBytes(b: ByteArray) {
	writeU32(b.size.toUInt())
	write(b)
}

private fun DataInput.readBytes(): ByteArray {
	val size = readU32().toIntOrThrow()
	val bytes = ByteArray(size)
	readFully(bytes)
	return bytes
}


private fun DataOutput.writeUtf8(s: String) {
	writeBytes(s.toByteArray(Charsets.UTF_8))
}

private fun DataInput.readUtf8(): String =
	readBytes().toString(Charsets.UTF_8)


private fun <T> DataOutput.writeArray(items: List<T>, itemWriter: (T) -> Unit) {
	writeU32(items.size.toUInt())
	for (item in items) {
		itemWriter(item)
	}
}

private fun <T> DataInput.readArray(itemReader: () -> T): List<T> {
	val size = readU32().toIntOrThrow()
	val list = ArrayList<T>(size)
	for (_i in 0 until size) {
		list.add(itemReader())
	}
	return list
}


private fun <T> DataOutput.writeOption(item: T?, itemWriter: (T) -> Unit) {
	if (item != null) {
		writeU32(1u)
		itemWriter(item)
	} else {
		writeU32(2u)
	}
}

private fun <T> DataInput.readOption(itemReader: () -> T): T? =
	when (val signal = readU32()) {
		1u -> itemReader()
		2u -> null
		else -> throw NoSuchElementException("unrecognized option signal: $signal")
	}
