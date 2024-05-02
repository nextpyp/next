package edu.duke.bartesaghi.micromon.linux.subprocess

import edu.duke.bartesaghi.micromon.SuspendCloseable
import edu.duke.bartesaghi.micromon.exists
import edu.duke.bartesaghi.micromon.linux.recvFramedOrNull
import edu.duke.bartesaghi.micromon.linux.sendFramed
import edu.duke.bartesaghi.micromon.slowIOs
import edu.duke.bartesaghi.micromon.use
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.div
import kotlin.math.min


class SubprocessClient(
	val name: String,
	private val subproc: Process,
	private val socket: SocketChannel
) : SuspendCloseable {

	private val log = LoggerFactory.getLogger("SubprocessClient:$name")

	companion object {

		suspend fun start(
			socketDir: Path,
			name: String,
			heapMiB: Int,
			socketTimeout: Duration
		): SubprocessClient {

			// start a JVM process with the same classpath as this one, but a different entry point
			val subproc = slowIOs {

				// get info about the current JVM
				val javaHomeDir = Paths.get(System.getProperty("java.home"))
				val javaBin = javaHomeDir / "bin" / "java"
				val classpath = System.getProperty("java.class.path")

				ProcessBuilder()
					.command(
						javaBin.toString(),
						"-cp", classpath,
						"-Xmx${heapMiB}m",
						"-Djava.awt.headless=true", // don't look for any graphics libraries
						SubprocessServer::class.qualifiedName,
						socketDir.toString(),
						name
					)
					.redirectInput(ProcessBuilder.Redirect.INHERIT)
					.redirectOutput(ProcessBuilder.Redirect.INHERIT)
					.redirectError(ProcessBuilder.Redirect.INHERIT)
					.start()
			}

			// wait for the socket to show up
			val socketPath = socketPath(socketDir, name)
			run {
				val start = Instant.now()
				while (true) {

					if (socketPath.exists()) {
						break
					}

					// but don't wait forver
					val elapsed = Duration.between(start, Instant.now())
					if (elapsed > socketTimeout) {
						throw NoSuchElementException("timed out waiting for subprocess socket file")
					}
					delay(100)
				}
			}

			// connect the socket
			val socket = slowIOs {
				SocketChannel.open(StandardProtocolFamily.UNIX).apply {
					configureBlocking(true) // should be blocking by default, but let's be explicit
					connect(UnixDomainSocketAddress.of(socketPath))
				}
			}

			return SubprocessClient(name, subproc, socket)
		}
	}


	// TODO: use some kind of weak map to cleanup responders that have forgotten to close?
	private val respondersByRequestId = HashMap<Long,Responder>()
	private val respondersLock = Mutex()
	private val nextRequestId = AtomicLong(1)

	private suspend fun <R> responders(block: suspend (HashMap<Long,Responder>) -> R): R =
		respondersLock.withLock {
			block(respondersByRequestId)
		}

	// deal with the sockets only on tasks confined to the IO thread pool
	private val scopeSocket = CoroutineScope(Dispatchers.IO)

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
				val responder = responders { it[response.requestId] }
				responder?.channel?.send(response.response)

			} catch (ex: ClosedSendChannelException) {
				// responder channel closed, safe to ignore
			} catch (t: Throwable) {
				log.error("Failed to receive response from socket", t)
			}
		}
	}


	override suspend fun closeAll() {

		// cleanup tasks and close the socket
		requestChannel.close()
		slowIOs {
			socket.close()
		}
		sender.join()
		receiver.join()

		// finally, stop the subprocess, if needed
		if (subproc.isAlive) {

			// process still running, send SIGTERM
			log.debug("Closing ...")
			subproc.destroy()

			withTimeoutOrNull(2000L) {
				subproc.onExit().await()
				log.debug("Exited!")
			} ?: run {

				// didn't exit, send SIGKILL this time
				log.warn("didn't respond to SIGTERM in time, sending SIGKILL ...")
				subproc.destroyForcibly()
				withTimeoutOrNull(2000L) {
					subproc.onExit().await()
					log.debug("Killed!")
				} ?: run {

					// still didn't exit, nothing else we can do
					log.warn("didn't respond to SIGKILL, unable to end subprocess!")
				}
			}
		}
	}


	private inner class Responder(private val requestId: Long) : SuspendCloseable {

		val channel = Channel<Response>(Channel.UNLIMITED)

		suspend fun send(request: Request) =
			requestChannel.send(RequestEnvelope(requestId, request))

		suspend fun recvResponse(): Response =
			channel.receive()

		override suspend fun closeAll() {
			responders { it.remove(requestId) }
		}
	}

	private fun makeRequestId(): Long =
		nextRequestId.getAndUpdate { i ->
			// just in case, roll over to 1 if we overflow a long
			if (i >= Long.MAX_VALUE) {
				1
			} else {
				// otherwise, just increment like normal
				i + 1
			}
		}

	private suspend fun request(request: Request): Responder {

		val requestId = makeRequestId()

		// register a new responder
		val responder = Responder(requestId)
		responders { it[requestId] = responder }

		requestChannel.send(RequestEnvelope(requestId, request))

		return responder
	}

	private suspend inline fun <reified T:Response> Responder.recv(): T {
		return when (val response = recvResponse()) {
			is T -> response // ok
			else -> throw UnexpectedResponseException(response)
		}
	}

	suspend fun ping() =
		request(Request.Ping)
			.use { responder ->
				responder.recv<Response.Pong>()
			}

	suspend fun readFile(path: Path): ByteArray =
		request(Request.ReadFile(path.toString()))
			.use { responder ->
				val buf = ByteArrayOutputStream()
				while (true) {
					when (val response = responder.recv<Response.ReadFile>()) {
						is Response.ReadFile.Fail -> throw IOException(response.reason)
						is Response.ReadFile.Chunk -> buf.write(response.chunk)
						is Response.ReadFile.Eof -> break
					}
				}
				buf.toByteArray()
			}

	interface FileWriter : SuspendCloseable {
		suspend fun write(buf: ByteArray)
	}

	suspend fun writeFile(path: Path): FileWriter {
		val responder = request(Request.WriteFile.Open(path.toString()))
		return when (val openResponse = responder.recv<Response.WriteFile>()) {
			is Response.WriteFile.Fail -> throw IOException(openResponse.reason)
			is Response.WriteFile.Ok -> object : FileWriter {

				override suspend fun write(buf: ByteArray) {

					// send the buffer in 32 KiB chunks, since using larger chunks than that seems to be pretty slow
					var start = 0
					while (start < buf.size) {
						val remaining = buf.size - start
						val size = min(remaining, 32*1024) // 32 KiB
						responder.send(Request.WriteFile.Chunk(buf.copyOfRange(start, start + size)))
						start += size
					}
				}

				override suspend fun closeAll() {
					responder.send(Request.WriteFile.Close)
					when (val closeResponse = responder.recv<Response.WriteFile>()) {
						is Response.WriteFile.Fail -> throw IOException(closeResponse.reason)
						is Response.WriteFile.Ok -> Unit // all is well
					}
					responder.close()
				}
			}
		}
	}
}


class UnexpectedResponseException(val response: Response) : RuntimeException("Unexpected response: $response")
