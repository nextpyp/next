package edu.duke.bartesaghi.micromon.imod

import edu.duke.bartesaghi.micromon.stream
import java.nio.file.Path


/**
 * See https://bio3d.colorado.edu/imod/doc/man/newstack.html
 */
object Newstack {

	data class Results(
		val exitCode: Int,
		val console: List<String>
	)

	/**
	 * Create a JPG image from the MRC file.
	 */
	fun jpg(mrcPath: Path, jpgPath: Path, bin: Int? = null): Results {

		// build the command
		val cmd = mutableListOf("newstack")
		cmd += listOf(
			"-input", mrcPath.toAbsolutePath().toString(),
			"-format", "jpg",
			"-mode", "0", // output byte values
			"-float", "1" // scale the values to the byte range
		)

		// apply binning if needed
		if (bin != null) {
			cmd += listOf(
				"-bin", "$bin"
			)
		}

		cmd += listOf(
			"-out", jpgPath.toAbsolutePath().toString()
		)

		// run newstack
		val process = ProcessBuilder()
			.command(cmd)
			.stream()
			.waitFor()

		// return the results
		return Results(
			process.exitCode,
			process.console.toList()
		)
	}

	class Exception(val msg: String, val mrcPath: Path, val results: Results) : RuntimeException("""
		|Error running `newstack` on MRC file: $msg
		|path: $mrcPath
		|exit code: ${results.exitCode}
		|console:
		|	${results.console.joinToString("\t\n")}
	""".trimMargin())
}
