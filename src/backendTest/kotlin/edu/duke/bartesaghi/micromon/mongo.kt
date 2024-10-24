package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.mongo.Database
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.time.Duration.Companion.seconds


class EphemeralMongo(
	private val dir: Path,
	private val process: ProcessStreamer
) : AutoCloseable {

	companion object {

		private val log = LoggerFactory.getLogger("EphemeralMongo")

		fun sockPath(dir: Path): Path =
			// NOTE: mongo will (inexplicably) convert socket filenames to lowercase
			//       so make sure to give it an all-lowercase filename to avoid any confusion
			dir.parent / "${dir.name.lowercase()}.sock"

		fun start(): EphemeralMongo {

			// pick a temporary folder to save the database files
			// NOTE: the "inMemory" storage engine option is only available in enterprise builds of MongoDB 4.4, so we can't use it here
			val dir = Files.createTempDirectory("ephemeralMongo-")

			val socketPath = sockPath(dir)

			// start the mongo process
			// https://www.mongodb.com/docs/v4.4/reference/program/mongod/
			val process = ProcessBuilder()
				.command(listOf(
					"/usr/bin/mongod",

					"--dbpath", dir.toString(),

					// bind only to our socket file, and not a network IP address
					"--nounixsocket",
					"--bind_ip", socketPath.toString(),

					// turn off authentication: there's no benefit to use it here and it's annoying to deal with
					"--noauth"
				))
				.stream()

			// look for early exit errors
			retryLoop(500) { _, timedOut ->
				if (!process.isRunning) {
					throw Error("""
						|MongoDB exited early:
						|  exit code: ${process.exitCode}
						|    console: ${process.console.joinToString("\n             ")}
					""".trimMargin())
				} else if (timedOut) {
					Tried.Return(Unit)
				} else {
					Thread.sleep(100)
					Tried.Waited()
				}
			}

			// wait for the socket file to appear
			retryLoop(5_000) { elapsedMs, timedOut ->
				if (socketPath.exists()) {
					Tried.Return(Unit)
				} else if (timedOut) {
					stop(process, dir)
					throw Error("Timed out waiting ${elapsedMs()} ms for database socket file to appear")
				} else {
					Thread.sleep(200)
					Tried.Waited()
				}
			}

			return EphemeralMongo(dir, process)
		}

		private fun stop(process: ProcessStreamer, dir: Path) {

			// try to stop the mongo process with eg SIGTERM
			process.terminate()
			process.waitFor(5.seconds)
			if (process.isRunning) {
				log.warn("""
					|Process failed to shut down cleanly within time limit:
					|    console: ${process.console.joinToString("\n             ")}
				""".trimMargin())
			}

			// cleanup the files
			try {
				dir.deleteDirRecursively()
			} catch (t: Throwable) {
				log.error("Failed to delete folder: $dir", t)
			}
		}
	}


	fun <R> useInstalled(block: () -> R): R = use {

		val path = sockPath(dir)
			.toString()
			// need to use URL encoding to get mongo to recognize the string as a socket path
			.replace("/", "%2F")

		Database.connect("mongodb://$path")
		try {
			block()
		} finally {
			Database.disconnect()
		}
	}

	override fun close() {
		stop(process, dir)
	}
}
