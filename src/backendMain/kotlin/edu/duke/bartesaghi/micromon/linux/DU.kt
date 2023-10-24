package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.*
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds


object DU {

	/**
	 * Returns the number of bytes used by the folder
	 */
	suspend fun countBytes(folder: Path): Long? = slowIOs f@{

		if (!folder.isDirectory()) {
			throw IllegalArgumentException("not a folder: $folder")
		}

		fun fail(msg: String, lines: List<String>? = null): Long? {
			Backend.log.warn(StringBuilder().apply {

				append("Failed to run du: ")
				append(msg)

				lines?.forEach {
					append("\n\tdu output: ")
					append(it)
				}
			}.toString())
			return null
		}

		try {

			// run `du`
			val du = ProcessBuilder()
				.command("du", "-bs", folder.toString())
				.stream()
				.waitFor(30.seconds)

			// get the first line of the result, if possible
			val lines = du.console.toList()
			val line = lines.firstOrNull()
				?: return@f fail("du failed to produce any output")

			// parse the line, it should look like, eg:
			// `201482311	./`
			line
				.split(' ', '\t')
				.firstOrNull()
				?.toLong()
				?: return@f fail("Failed to parse a size from the du output", lines)

		} catch (t: Throwable) {
			Backend.log.error("Failed to run du", t.cleanupStackTrace())
			fail("internal error /\\/\\ see above for exception info /\\/\\")
		}
	}
}
