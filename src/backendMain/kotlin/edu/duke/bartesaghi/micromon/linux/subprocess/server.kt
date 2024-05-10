package edu.duke.bartesaghi.micromon.linux.subprocess

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.linux.recvFramedOrNull
import edu.duke.bartesaghi.micromon.linux.sendFramed
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.inputStream


class SubprocessServer(
	val log: Logger,
	val socketDir: Path,
	val name: String,
) : SuspendCloseable {

	companion object {

		/**
		 * Entry point for the subprocess server
		 */
		@JvmStatic
		fun main(args: Array<String>) {

			// read the args
			val socketDir = args.getOrNull(0)
				?.let { Paths.get(it) }
				?: throw NoSuchElementException("missing socketDir argument")
			val name = args.getOrNull(1)
				?: throw NoSuchElementException("missing name argument")

			// start the log
			val log = LoggerFactory.getLogger("SubprocessServer:$name")

			runBlocking {

				val task = launch(Dispatchers.IO) {
					SubprocessServer(log, socketDir, name)
						.use { it.serve() }
				}

				// add a shutdown hook so we can try to exit gracefully for things like SIGTERM
				Runtime.getRuntime().addShutdownHook(Thread {
					// NOTE: the JVM process exits when this thread is finished,
					//       so make sure we finish all the cleanup before then

					log.info("JVM shutdown requested ...")

					// send a cancel exception to the task
					task.cancel()

					// wait for the task to finish (the single-threaded way), but don't wait forever
					retryLoop(10_000) { elapsedMs, timedOut ->
						if (task.isCompleted) {
							Tried.Return(Unit)
						} else if (timedOut) {
							log.warn("Timed out waiting for cleanup")
							Tried.Return(Unit)
						} else {
							Thread.sleep(100)
							log.trace("waited {} ms", elapsedMs())
							Tried.Waited()
						}
					}
				})

				task.join()
			}

			log.info("Finished")
		}
	}


	private val scopeSocket = CoroutineScope(Dispatchers.IO)
	private val scopeConnections = CoroutineScope(Dispatchers.IO)
	private val scopeDispatch = CoroutineScope(Dispatchers.Default)

	// start a unix socket server
	private val socketPath = socketPath(socketDir, name)
	private val socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
		configureBlocking(true) // should be blocking by default, but let's be explicit
		socketPath.parent.createDirsIfNeeded()
		bind(UnixDomainSocketAddress.of(socketPath))
		log.info("Listening on socket: {}", socketPath)
	}

	override suspend fun closeAll() {

		// close any connections
		scopeConnections.cancel()

		// close the socket
		slowIOs {
			socket.close()
		}

		// cleanup the socket file
		if (socketPath.exists()) {
			socketPath.delete()
		}
	}

	private suspend fun serve() {

		log.info("Started")

		while (true) {

			// listen for incoming connections
			val conn = try {

				// put the blocking accept() call in a task so it we can handle task cancellations here
				// otherwise, the blocking accept() won't get interrupted by the cancellation exception
				scopeSocket
					.async { socket.accept() }
					.await()

			} catch (ex: AsynchronousCloseException) {
				// socket listener closed by another task (ie close()), stop serving
				break
			} catch (ex: CancellationException) {
				// task canceled (ie by shutdown hook), stop serving
				break
			} catch (t: Throwable) {
				log.error("Failed to accept socket connection", t)
				continue
			}

			// drive the connection on a dedicated task
			scopeConnections.launch {
				conn.use { serveConnection(it) }
			}
		}

		log.info("Stopped")
	}

	private suspend fun serveConnection(socket: SocketChannel) {
		while (true) {
			try {

				// wait for the next message from the socket
				// NOTE: use the same task structure as serve() to respond to cancellations
				val envelope = scopeSocket
					.async { socket.recvFramedOrNull() }
					.await()
					?.let { RequestEnvelope.decode(it) }
					?: break

				// dispatch the request on a task
				val responder = Responder(envelope.requestId, socket)
				scopeDispatch.launch {
					dispatch(envelope.request, responder)
				}

			} catch (ex: CancellationException) {
				break
			} catch (t: Throwable) {
				log.error("Failed to receive response from socket", t)
			}
		}
	}

	private inner class Responder(
		val requestId: Long,
		val socket: SocketChannel
	) {

		suspend fun send(response: Response) {
			val envelope = ResponseEnvelope(requestId, response)
			scopeSocket
				.async { socket.sendFramed(envelope.encode()) }
				.await()
		}
	}

	private suspend fun dispatch(request: Request, responder: Responder) =
		when (request) {
			is Request.Ping -> dispatchPing(responder)
			is Request.ReadFile -> dispatchReadFile(request, responder)
			is Request.WriteFile -> dispatchWriteFile(request, responder)
		}

	private suspend fun dispatchPing(responder: Responder) =
		responder.send(Response.Pong) // easy peasy =D

	private suspend fun dispatchReadFile(request: Request.ReadFile, responder: Responder) = slowIOs {

		// try to open the file
		try {
			val path = Paths.get(request.path)
			path.inputStream().use { input ->

				// read the file in chunks
				// NOTE: empirically, 32 KiB chunks seems to transfer much faster than 1 MiB chunks,
				//       and a little bit faster than 16 and 64 KiB chunks
				val buf = ByteArray(32*1024) // 32 KiB
				while (true) {

					// read a chunk
					val bytesRead = input.read(buf)
					if (bytesRead <= 0) {
						break
					}

					// send the chunk back
					// NOTE: sending is async, so send a copy of the buffer before reading the next chunk into the buffer
					// TODO: the send queue is unbounded, so do we need any kind of rate control/back pressure here?
					responder.send(Response.ReadFile.Chunk(buf.copyOfRange(0, bytesRead)))
				}

				responder.send(Response.ReadFile.Eof)
			}
		} catch (t: Throwable) {
			responder.send(Response.ReadFile.Fail(t.message ?: ""))
		}
	}

	private val fileWritersByRequestId = HashMap<Long,FileWriter>()
	private val fileWritersLock = Mutex()

	private suspend fun <R> fileWriters(block: suspend (HashMap<Long,FileWriter>) -> R): R =
		fileWritersLock.withLock {
			block(fileWritersByRequestId)
		}

	private class FileWriter(private val out: FileOutputStream) {

		var t: Throwable? = null

		/** keep concurrent requests for the same file write from clashing with each other */
		val mutex = Mutex()

		suspend fun lock(block: suspend (FileOutputStream) -> Unit) {
			mutex.withLock {
				block(out)
			}
		}
	}

	private suspend fun dispatchWriteFile(request: Request.WriteFile, responder: Responder) {
		when (request) {

			is Request.WriteFile.Open -> {

				// try to open the file
				val out = try {
					slowIOs {
						FileOutputStream(request.path)
					}
				} catch (t: Throwable) {
					responder.send(Response.WriteFile.Fail(t.message ?: ""))
					return
				}

				// save the open file for later
				fileWriters { it[responder.requestId] = FileWriter(out) }

				// send the response
				responder.send(Response.WriteFile.Ok)
			}

			is Request.WriteFile.Chunk -> {

				val writer = fileWriters { it[responder.requestId] }
					?: return

				writer.lock { out ->
					try {
						slowIOs {
							out.write(request.chunk)
						}
					} catch (t: Throwable) {
						// save the exception for later
						if (writer.t != null) {
							writer.t = t
						}
					}
				}

				// no need for a response here, this isn't TCP after all =P
			}

			is Request.WriteFile.Close -> {

				val writer = fileWriters { it.remove(responder.requestId) }
					?: run {
						responder.send(Response.WriteFile.Fail("unknown request: ${responder.requestId}"))
						return
					}

				// send any pending errors
				writer.t?.let { t ->
					responder.send(Response.WriteFile.Fail(t.message ?: ""))
					return
				}

				// try to close the file
				try {
					writer.lock { out ->
						slowIOs {
							out.close()
						}
					}
				} catch (t: Throwable) {
					responder.send(Response.WriteFile.Fail(t.message ?: ""))
					return
				}

				// is well
				responder.send(Response.WriteFile.Ok)
			}
		}
	}
}
