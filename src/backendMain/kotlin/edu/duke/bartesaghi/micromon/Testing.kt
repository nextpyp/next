package edu.duke.bartesaghi.micromon

import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.io.path.bufferedReader
import kotlin.io.path.div


object Testing {

	/**
	 * Turn on to switch various things into testing mode.
	 * Do it as the first call in main, so singleton init() methods can see it.
	 */
	var active = false

	val pypDir: Path? by lazy {

		// read the local.properties file
		val localProps = Properties().apply {
			Paths.get("local.properties").bufferedReader().use { reader ->
				load(reader)
			}
		}

		(localProps["pypDir"] as String?)
			?.let { Paths.get(it) }
	}

	val pypDirOrThrow: Path get() =
		pypDir
			?.let { it / "config" / "pyp_config.toml" }
			?: throw Error("no pypDir configured in local.properties")

}


fun assertEq(obs: Any?, exp: Any?, msg: String? = null) {
	if (obs != exp) {
		throw AssertionError("""
			|
			|  expected:  $exp
			|observered:  $obs
			|       msg:  ${msg ?: ""}
		""".trimMargin())
	}
}
