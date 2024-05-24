package edu.duke.bartesaghi.micromon.linux.userprocessor

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.linux.*
import edu.duke.bartesaghi.micromon.linux.hostprocessor.HostProcessor
import edu.duke.bartesaghi.micromon.linux.hostprocessor.Response as HostProcessorResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.PrintStream
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.math.min


class UserProcessorException(val path: Path, val problems: List<String>) : RuntimeException("""
		|Failed to use user-processor executable at: $path
		|	${problems.joinToString("\n\t")}
	""".trimMargin())


class UserProcessor(
	val path: Path,
	val username: String,
	private val log: Logger,
	private val subproc: HostProcessor.StreamingProcess,
	private val socket: SocketChannel
) : SuspendCloseable {

	companion object {

		private val consoleScope = CoroutineScope(Dispatchers.IO)

		private val execDir = Config.instance.web.sharedDir / "user-processors"
		private val socketDir = Config.instance.web.localDir / "user-processors"

		private suspend fun find(hostProcessor: HostProcessor, username: String): Path {

			val path = execDir / "user-processor-$username"
			val failures = ArrayList<String>()

			// find the uid
			val uid = hostProcessor.uid(username)
				?: throw UserProcessorException(path, listOf("Unknown username: $username"))

			// the user should never be root
			if (uid == 0u) {
				throw UserProcessorException(path, listOf("Cannot run a user-processor as root"))
			}

			// find the user-specific runas executable
			if (!path.exists()) {
				throw UserProcessorException(path, listOf("File not found: $path"))
			}

			// check the unix permissions
			val stat = Filesystem.stat(path)

			// the file should be owned by the given username
			if (stat.uid != uid) {
				val fileUsername = hostProcessor.username(stat.uid)
				failures.add("File permissions: Should be owned by $username, not $fileUsername")
			}

			// owner should have: setuid
			if (!stat.isSetUID) {
				failures.add("File permissions: Should be setuid")
			}

			// group should have: r-x
			if (!stat.isGroupRead) {
				failures.add("File permissions: Group should read")
			}
			if (stat.isGroupWrite) {
				failures.add("File permissions: Group must not write")
			}
			if (!stat.isGroupExecute) {
				failures.add("File permissions: Group should execute")
			}

			// other should have: r-- or ---
			if (stat.isOtherWrite) {
				failures.add("File permissions: Others must not write")
			}
			if (stat.isOtherExecute) {
				failures.add("File permissions: Others must not execute")
			}

			// and the file should be owned by any group among this user's groups
			val websiteUid = Filesystem.getUid()
			val websiteGids = hostProcessor.gids(websiteUid)
			if (websiteGids == null || stat.gid !in websiteGids) {
				val micromonUsername = hostProcessor.username(websiteUid)
				val fileGroupname = hostProcessor.groupname(stat.gid)
				failures.add("File permissions: website user $micromonUsername is not a member of group $fileGroupname")
			}

			if (failures.isNotEmpty()) {
				throw UserProcessorException(path, failures)
			}

			return path
		}

		suspend fun start(
			hostProcessor: HostProcessor,
			username: String,
			socketTimeoutMs: Long,
			tracingLog: String? = null
		): UserProcessor {

			val log = LoggerFactory.getLogger("UserProcessor:$username")

			// start a user processor via the host processor
			val (path, subproc) = slowIOs {

				log.debug("Starting user processor")
				val path = find(hostProcessor, username)
				val subproc = hostProcessor.execStream(
					Command(
						path.toString(),
						ArrayList<String>().apply {
							add("daemon")
							if (tracingLog != null) {
								addAll(listOf("--log", tracingLog))
							}
						}
					),
					dir = socketDir,
					stdout = true,
					stderr = true
				)

				// pipe the subprocess' stdout,stderr to this process' stdout,stderr
				consoleScope.launch {

					fun pipe(chunk: ByteArray, out: PrintStream) {
						var wroteLine = false
						chunk.toString(Charsets.UTF_8)
							.lineSequence()
							.filter { it.isNotEmpty() || !wroteLine }
							.forEach {
								out.println(it)
								wroteLine = true
							}
					}

					while (true) {
						when (val event = subproc.recvEvent()) {
							is HostProcessorResponse.ProcessEvent.Console -> when (event.kind) {
								HostProcessorResponse.ProcessEvent.ConsoleKind.Stdout -> pipe(event.chunk, System.out)
								HostProcessorResponse.ProcessEvent.ConsoleKind.Stderr -> pipe(event.chunk, System.err)
							}
							is HostProcessorResponse.ProcessEvent.Fin -> break
						}
					}
				}

				path to subproc
			}

			// NOTE: the subprocess has started now:
			//       don't exit this function without either stopping the process,
			//       or returning the client instance

			// try to connect to the subprocess socket
			val socket = try {
				val socketPath = socketDir / "user-processor-${subproc.pid}-$username"
				log.debug("expecting socket file: {}", socketPath)
				retryLoop(socketTimeoutMs) { elapsedMs, timedOut ->

					suspend fun tryAgainOrTimeout(): Tried<SocketChannel> =
						if (timedOut) {
							log.debug("timed out")
							throw TimeoutException("timed out trying to connect")
						} else {
							delay(100)
							log.trace("waited {} ms", elapsedMs())
							Tried.Waited()
						}

					// make sure the socket file is visible first
					if (slowIOs { !socketPath.exists() }) {
						log.trace("socket file not yet visible")
						return@retryLoop tryAgainOrTimeout()
					}

					try {
						Tried.Return(slowIOs {
							SocketChannel.open(StandardProtocolFamily.UNIX)
								.apply {
									configureBlocking(true) // should be blocking by default, but let's be explicit
									connect(UnixDomainSocketAddress.of(socketPath))
								}
						})
					} catch (ex: ConnectException) {
						if (ex.message == "Connection refused") {
							log.debug("connection refused")
							// sometimes the socket file is visible, but the server isn't quite ready yet
							tryAgainOrTimeout()
						} else {
							log.warn("connect() threw ConnectException with an unrecognized message: {} \"{}\"", ex::class.qualifiedName, ex.message)
							throw ex
						}
					} catch (ex: AsynchronousCloseException) {
						// something closed the socket while we were waiting to connect
						// still don't know what does this, but we should recover by just retrying until the timeout
						log.debug("socket closed concurrently while waiting to connect")
						tryAgainOrTimeout()
					} catch (t: Throwable) {
						log.warn("connect() threw an unrecognized error: {} \"{}\"", t::class.qualifiedName, t.message)
						throw t
					}
				}
			} catch (t: Throwable) {

				// try to cleanup the subprocess before failing
				subproc.close(log)

				throw t
			}
			log.debug("connected to server")

			return UserProcessor(path, username, log, subproc, socket)
		}

		suspend fun HostProcessor.StreamingProcess.close(log: Logger) {

			var isAlive = status()
			log.debug("cleaning up subprocess, alive? {}", isAlive)
			if (isAlive) {

				// process still running, send SIGTERM
				log.debug("sending SIGTERM")
				kill()

				retryLoop(5_000L) { _, timedOut ->
					isAlive = status()
					if (!isAlive) {
						log.debug("Exited!")
						Tried.Return(Unit)
					} else if (timedOut) {
						log.warn("didn't respond to SIGTERM in time, abandoning process")
						// TODO: need a SIGKILL?
						Tried.Return(Unit)
					} else {
						delay(100)
						Tried.Waited()
					}
				}
			}
		}
	}


	// TODO: use some kind of weak map to cleanup responders that have forgotten to close?
	private val respondersByRequestId = HashMap<Long,Responder>()
	private val respondersLock = Mutex()
	private val requestIds = U32Counter()

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
				val responder = responders { it[response.requestId.toLong()] }
				responder?.channel?.send(response.response)

			} catch (ex: ClosedSendChannelException) {
				// responder channel closed, safe to ignore
			} catch (t: Throwable) {
				log.error("Failed to receive response from socket", t)
			}
		}
	}


	override suspend fun closeAll() {

		log.debug("closing ...")

		// cleanup tasks and close the socket
		requestChannel.close()
		slowIOs {
			socket.close()
		}
		sender.join()
		receiver.join()

		// finally, stop the subprocess, if needed
		subproc.close(log)
	}

	private inner class Responder(private val requestId: UInt) : SuspendCloseable {

		val channel = Channel<Response>(Channel.UNLIMITED)

		suspend fun send(request: Request) =
			requestChannel.send(RequestEnvelope(requestId, request))

		suspend fun recv(): Response =
			channel.receive()

		override suspend fun closeAll() {
			responders { it.remove(requestId.toLong()) }
		}
	}

	private suspend fun request(request: Request): Responder {

		val requestId = requestIds.next()

		// register a new responder
		val responder = Responder(requestId)
		responders { it[requestId.toLong()] = responder }

		requestChannel.send(RequestEnvelope(requestId, request))

		return responder
	}

	suspend fun ping() =
		request(Request.Ping)
			.use { responder ->
				responder.recv().cast<Response.Pong>()
			}

	suspend fun uids(): Response.Uids =
		request(Request.Uids)
			.use {
				it.recv().cast<Response.Uids>()
			}

	suspend fun readFile(path: Path): ByteArray =
		request(Request.ReadFile(path.toString()))
			.use { responder ->

				// wait for the open
				val open = responder.recv()
					.cast<Response.ReadFile>()
					.response
					.cast<Response.ReadFile.Open>()

				// allocate a buffer for the file
				val buf = ByteArray(run {
					val i = open.bytes.toInt()
					if (i.toULong() != open.bytes) {
						throw UnsupportedOperationException("file too large to read: ${open.bytes} bytes")
					}
					i
				})

				// make sure the chunks come in the correct sequence
				// TODO: do we need to re-order?
				var sequence = 1u
				fun checkSequence(obs: UInt) {
					if (obs != sequence) {
						throw IllegalStateException("Expected sequence $sequence, but got $obs")
					}
					sequence += 1u
				}

				// read in all the chunks
				var bytesRead = 0
				while (bytesRead < buf.size) {
					val chunk = responder.recv()
						.cast<Response.ReadFile>()
						.response
						.cast<Response.ReadFile.Chunk>()
					checkSequence(chunk.sequence)
					chunk.data.copyInto(buf, bytesRead)
					bytesRead += chunk.data.size
				}

				// wait for close
				val close = responder.recv()
					.cast<Response.ReadFile>()
					.response
					.cast<Response.ReadFile.Close>()
				checkSequence(close.sequence)

				buf
			}

	interface FileWriter : SuspendCloseable {
		/** writes a single chunk */
		suspend fun write(chunk: ByteArray)
		/** writes the whole buffer using efficiently-sized chunks */
		suspend fun writeAll(buf: ByteArray)
	}

	suspend fun writeFile(path: Path, append: Boolean = false): FileWriter {

		// open the file
		val responder = request(Request.WriteFile.Open(path.toString(), append).into())
		responder.recv()
			.cast<Response.WriteFile>()
			.response
			.cast<Response.WriteFile.Opened>()

		// send back a writer object
		var sequence = 1u
		return object : FileWriter {

			override suspend fun write(chunk: ByteArray) {
				responder.send(Request.WriteFile.Chunk(sequence, chunk).into())
				sequence += 1u
				// NOTE: no response expected here
			}

			override suspend fun writeAll(buf: ByteArray) {

				// send the buffer in 32 KiB chunks, since using larger chunks than that seems to be pretty slow
				var start = 0
				while (start < buf.size) {
					val remaining = buf.size - start
					val size = min(remaining, 32*1024) // 32 KiB
					write(buf.copyOfRange(start, start + size))
					start += size
				}
			}

			override suspend fun closeAll() {
				try {
					responder.send(Request.WriteFile.Close(sequence).into())
					responder.recv()
						.cast<Response.WriteFile>()
						.response
						.cast<Response.WriteFile.Closed>()
				} finally {
					responder.close()
				}
			}
		}
	}
}
