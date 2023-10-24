package edu.duke.bartesaghi.micromon.imod

import edu.duke.bartesaghi.micromon.stream
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter


/**
 * See https://bio3d.colorado.edu/imod/doc/man/header.html
 */
object Header {

	val log = LoggerFactory.getLogger(javaClass)

	data class Results(
		val exitCode: Int,
		val console: List<String>,
		val size: Size?,
		val minDensity: Double?,
		val maxDensity: Double?,
		val meanDensity: Double?,
		val titles: List<Title>
	)

	data class Size(
		val width: Int,
		val height: Int
	)

	data class Title(
		val id: String,
		val desc: String,
		val timestamp: Instant
	)

	fun run(path: Path): Results {

		// run header
		val process = ProcessBuilder()
			.command(
				"header",
				path.toAbsolutePath().toString()
			)
			.stream()
			.waitFor()

		var size: Size? = null
		var minDensity: Double? = null
		var maxDensity: Double? = null
		var meanDensity: Double? = null
		val titles = ArrayList<Title>()

		var titlesMode = false

		// parse the data from the console
		val console = process.console.toList()
		for (line in console) {

			// skip blank lines
			if (line.isBlank()) {
				continue
			}

			if (!titlesMode) {

				if (line.startsWith(SizeTag)) {

					// e.g.:
					// Number of columns, rows, sections .....    7420    7676       1

					val parts = line.getTagPayload(SizeTag)
					val width = parts.getOrNull(0)?.toIntOrNull()
					val height = parts.getOrNull(1)?.toIntOrNull()
					if (width != null && height != null) {
						size = Size(width, height)
					}

				} else if (line.startsWith(MinDensityTag)) {

					// e.g.:
					// Minimum density .......................   0.0000

					val parts = line.getTagPayload(MinDensityTag)
					minDensity = parts.getOrNull(0)?.toDoubleOrNull()

				} else if (line.startsWith(MaxDensityTag)) {

					// e.g.:
					// Maximum density .......................   1.2175

					val parts = line.getTagPayload(MaxDensityTag)
					maxDensity = parts.getOrNull(0)?.toDoubleOrNull()

				} else if (line.startsWith(MeanDensityTag)) {

					// e.g.:
					// Mean density ..........................  0.42338

					val parts = line.getTagPayload(MeanDensityTag)
					meanDensity = parts.getOrNull(0)?.toDoubleOrNull()

				} else if (line.matches(TitlesRegex)) {

					// e.g.:
					//     3 Titles :

					titlesMode = true
				}

			} else { // titles mode

				// e.g.:
				// CCDERASER: Bad points replaced with interpolated values 19-Aug-19  17:10:15
				// NEWSTACK: Images copied, transformed                    19-Aug-19  17:11:12
				// AVGSTACK:   38 sections averaged.                       19-Aug-19  17:11:35

				val parts = line.split(" ")
					.filter { it.isNotBlank() }

				val id = parts.getOrNull(0)?.let {
					it.substring(0, it.length - 1)
				}
				val timestamp = try {
					LocalDateTime
						.parse(
							"${parts[parts.size - 2]} ${parts[parts.size - 1]}",
							DateTimeFormatter.ofPattern("yy-MMM-dd HH:mm:ss")
							// TODO: is this ymd or dmy?
						)
						.atZone(ZoneId.systemDefault())
						.toInstant()
				} catch (e: Throwable) {
					log.error("Error parsing timestamp in imod header $line @ $path", e)
					null
				}

				val desc = parts
					.takeIf { it.size >= 4 }
					?.let { it.subList(1, it.size - 2).joinToString(" ") }

				if (id != null && timestamp != null && desc != null) {
					titles.add(Title(id, desc, timestamp))
				}
			}
		}

		// return the results
		return Results(
			process.exitCode,
			console,
			size,
			minDensity,
			maxDensity,
			meanDensity,
			titles
		)
	}

	class Exception(val msg: String, val path: Path, val results: Results) : RuntimeException("""
		|Error running `header` on MRC file: $msg
		|path: $path
		|exit code: ${results.exitCode}
		|console:
		|	${results.console.joinToString("\t\n")}
	""".trimMargin())
}

private const val SizeTag = " Number of columns, rows, sections ....."
private const val MinDensityTag = " Minimum density ......................."
private const val MaxDensityTag = " Maximum density ......................."
private const val MeanDensityTag = " Mean density .........................."

private val TitlesRegex = Regex("\\s*\\d+ Titles :\\s*")

private fun String.getTagPayload(tag: String) =
	substring(tag.length)
		.split(" ")
		.filter { it.isNotBlank() }
