package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.use
import edu.duke.bartesaghi.micromon.SuspendCloseable
import edu.duke.bartesaghi.micromon.linux.HostProcessor.Connection.Responder
import edu.duke.bartesaghi.micromon.slowIOs
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
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
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
		private val respondersByRequestId = HashMap<UInt,ConnectionResponder>()
		private val respondersLock = Mutex()
		private val nextRequestId = AtomicLong(1)

		private suspend fun <R> responders(block: suspend (HashMap<UInt,ConnectionResponder>) -> R): R =
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
					socket.send(request)
				} catch (t: Throwable) {
					log.error("Failed to send request", t)
				}
			}

			// task exiting: close request channel so senders get an error instead of just buffering the traffic
			requestChannel.close()
		}

		// make a task to handle receiving messages
		private val responseChannel = Channel<Response>(Channel.UNLIMITED)
		private val receiver = scopeSocket.launch {

			// listen for responses from the socket
			while (true) {
				try {

					// wait for the next response
					val response = socket.recvResponse()

					// dispatch to a responder
					val responder = responders { it[response.id] }
					responder?.channel?.send(response.response)

				} catch (ex: ClosedChannelException) {
					// channel closed, exit the receiver task
					break
				} catch (ex: AsynchronousCloseException) {
					// socket closed, exit the receiver task
					break
				} catch (ex: BufferUnderflowException) {
					// error in decoding response, host processor and website got out of sync somehow
					// probably not recoverable, so just abort listener task
					log.error("Protocol Desync!", ex)
					break
				} catch (t: Throwable) {
					log.error("Failed to handle response", t)
				}
			}

			// task exiting: close the response channel so receivers get an error
			responseChannel.close()
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

			override suspend fun close() {
				responders { it.remove(requestId) }
			}
		}
	}


	suspend inline fun <reified T:Response> Responder.recv(): T {
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
	}

	private inner class StreamingProcessImpl(
		pid: UInt,
		private val responder: Responder
	) : ProcessImpl(pid), StreamingProcess {

		override suspend fun close() {
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


private fun SocketChannel.send(request: RequestEnvelope) {
	val encoded = request.encode()
	write(ByteBuffer.allocate(4).apply {
		putInt(encoded.size)
		flip()
	})
	write(ByteBuffer.wrap(encoded))
}

private fun SocketChannel.readAll(size: Int): ByteBuffer {
	val buf = ByteBuffer.allocate(size)
	while (true) {
		val bytesRead = read(buf)
		if (bytesRead == -1) {
			// end of stream: can't read any more, so throw
			throw AsynchronousCloseException()
		}
		if (buf.position() < buf.capacity()) {
			// wait a bit for more to show up
			Thread.sleep(100)
		} else {
			break
		}
	}
	buf.flip()
	return buf
}

private fun SocketChannel.recvResponse(): ResponseEnvelope {

	// read the message size
	val sizeBuf = readAll(4)
	val size = sizeBuf.getInt().toUInt().toIntOrThrow()

	// read the message
	val buf = readAll(size)
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

	class Exec(
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

	class Status(
		val pid: UInt
	) : Request {
		companion object {
			const val ID: UInt = 3u
		}
	}

	class WriteStdin(
		val pid: UInt,
		val chunk: ByteArray
	) : Request {
		companion object {
			const val ID: UInt = 4u
		}
	}

	class CloseStdin(
		val pid: UInt
	) : Request {
		companion object {
			const val ID: UInt = 5u
		}
	}

	class Kill(
		val pid: UInt
	) : Request {
		companion object {
			const val ID: UInt = 6u
		}
	}

	class Username(
		val uid: UInt
	) : Request {
		companion object {
			const val ID: UInt = 7u
		}
	}

	class Uid(
		val username: String
	) : Request {
		companion object {
			const val ID: UInt = 8u
		}
	}

	class Groupname(
		val gid: UInt
	) : Request {
		companion object {
			const val ID: UInt = 9u
		}
	}

	class Gid(
		val groupname: String
	) : Request {
		companion object {
			const val ID: UInt = 10u
		}
	}

	class Gids(
		val uid: UInt
	) : Request {
		companion object {
			const val ID: UInt = 11u
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

			is Request.Status -> {
				out.writeU32(Request.Status.ID)
				out.writeU32(request.pid)
			}

			is Request.WriteStdin -> {
				out.writeU32(Request.WriteStdin.ID)
				out.writeU32(request.pid)
				out.writeBytes(request.chunk)
			}

			is Request.CloseStdin -> {
				out.writeU32(Request.CloseStdin.ID)
				out.writeU32(request.pid)
			}

			is Request.Kill -> {
				out.writeU32(Request.Kill.ID)
				out.writeU32(request.pid)
			}

			is Request.Username -> {
				out.writeU32(Request.Username.ID)
				out.writeU32(request.uid)
			}

			is Request.Uid -> {
				out.writeU32(Request.Uid.ID)
				out.writeUtf8(request.username)
			}

			is Request.Groupname -> {
				out.writeU32(Request.Groupname.ID)
				out.writeU32(request.gid)
			}

			is Request.Gid -> {
				out.writeU32(Request.Gid.ID)
				out.writeUtf8(request.groupname)
			}

			is Request.Gids -> {
				out.writeU32(Request.Gids.ID)
				out.writeU32(request.uid)
			}
		}

		return bos.toByteArray()
	}

	companion object {

		fun decode(msg: ByteArray): RequestEnvelope {

			val input = DataInputStream(ByteArrayInputStream(msg))

			val id = input.readU32()

			val request: Request = when (val typeId = input.readU32()) {

				Request.Ping.ID -> Request.Ping()

				Request.Exec.ID -> Request.Exec(
					program = input.readUtf8(),
					args = input.readArray {
						input.readUtf8()
					},
					streamStdin = input.readBoolean(),
					streamStdout = input.readBoolean(),
					streamStderr = input.readBoolean(),
					streamFin = input.readBoolean()
				)

				Request.Status.ID -> Request.Status(
					pid = input.readU32()
				)

				Request.WriteStdin.ID -> Request.WriteStdin(
					pid = input.readU32(),
					chunk = input.readBytes()
				)

				Request.CloseStdin.ID -> Request.CloseStdin(
					pid = input.readU32()
				)

				Request.Kill.ID -> Request.Kill(
					pid = input.readU32()
				)

				Request.Username.ID -> Request.Username(
					uid = input.readU32()
				)

				Request.Uid.ID -> Request.Uid(
					username = input.readUtf8()
				)

				Request.Groupname.ID -> Request.Groupname(
					gid = input.readU32()
				)

				Request.Gid.ID -> Request.Gid(
					groupname = input.readUtf8()
				)

				Request.Gids.ID -> Request.Gids(
					uid = input.readU32()
				)

				else -> throw NoSuchElementException("unrecognized response type id: $typeId")
			}

			return RequestEnvelope(id, request)
		}
	}
}


sealed interface Response {

	class Error(val reason: String) : Response {
		companion object {
			const val ID: UInt = 1u
		}
	}

	class Pong : Response {
		companion object {
			const val ID: UInt = 2u
		}
	}

	class Exec(val response: Response) : Response {
		companion object {
			const val ID: UInt = 3u
		}

		sealed interface Response

		class Success(val pid: UInt) : Response {
			companion object {
				const val ID: UInt = 1u
			}
		}

		class Failure(val reason: String) : Response {
			companion object {
				const val ID: UInt = 2u
			}
		}
	}

	class ProcessEvent(val event: Event) : Response {
		companion object {
			const val ID: UInt = 4u
		}

		sealed interface Event

		class Console(val kind: ConsoleKind, val chunk: ByteArray): Event {
			companion object {
				const val ID: UInt = 1u
			}
		}

		class Fin(val exitCode: Int?) : Event {
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

	class Status(val isRunning: Boolean) : Response {
		companion object {
			const val ID: UInt = 5u
		}
	}

	class Username(val username: String?): Response {
		companion object {
			const val ID: UInt = 6u
		}
	}

	class Uid(val uid: UInt?): Response {
		companion object {
			const val ID: UInt = 7u
		}
	}

	class Groupname(val groupname: String?): Response {
		companion object {
			const val ID: UInt = 8u
		}
	}

	class Gid(val gid: UInt?): Response {
		companion object {
			const val ID: UInt = 9u
		}
	}

	class Gids(val gids: List<UInt>?): Response {
		companion object {
			const val ID: UInt = 10u
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

			is Response.Status -> {
				out.writeU32(Response.Status.ID)
				out.writeBoolean(response.isRunning)
			}

			is Response.Username -> {
				out.writeU32(Response.Username.ID)
				out.writeOption(response.username) {
					out.writeUtf8(it)
				}
			}

			is Response.Uid -> {
				out.writeU32(Response.Uid.ID)
				out.writeOption(response.uid) {
					out.writeU32(it)
				}
			}

			is Response.Groupname -> {
				out.writeU32(Response.Groupname.ID)
				out.writeOption(response.groupname) {
					out.writeUtf8(it)
				}
			}

			is Response.Gid -> {
				out.writeU32(Response.Gid.ID)
				out.writeOption(response.gid) {
					out.writeU32(it)
				}
			}

			is Response.Gids -> {
				out.writeU32(Response.Gids.ID)
				out.writeOption(response.gids) { gids ->
					out.writeArray(gids) { gid ->
						out.writeU32(gid)
					}
				}
			}
		}

		return bos.toByteArray()
	}


	companion object {

		fun decode(msg: ByteArray): ResponseEnvelope {

			val input = DataInputStream(ByteArrayInputStream(msg))

			val id = input.readU32()

			val response: Response = when (val responseTypeId = input.readU32()) {

				Response.Error.ID -> Response.Error(
					reason = input.readUtf8()
				)

				Response.Pong.ID -> Response.Pong()

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

				Response.Status.ID -> Response.Status(
					isRunning = input.readBoolean()
				)

				Response.Username.ID -> Response.Username(
					username = input.readOption {
						input.readUtf8()
					}
				)

				Response.Uid.ID -> Response.Uid(
					uid = input.readOption {
						input.readU32()
					}
				)

				Response.Groupname.ID -> Response.Groupname(
					groupname = input.readOption {
						input.readUtf8()
					}
				)

				Response.Gid.ID -> Response.Gid(
					gid = input.readOption {
						input.readU32()
					}
				)
				Response.Gids.ID -> Response.Gids(
					gids = input.readOption {
						input.readArray {
							input.readU32()
						}
					}
				)


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
	// NOTE: writeInt() is big-endian
	writeInt(i.toInt())

private fun DataInput.readU32(): UInt =
	// NOTE: readInt() is big-endian
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
