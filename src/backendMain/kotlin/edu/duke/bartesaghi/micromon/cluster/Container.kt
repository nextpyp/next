package edu.duke.bartesaghi.micromon.cluster

import edu.duke.bartesaghi.micromon.Config
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.div


sealed interface Container {

	val sifPath: Path
	val binds: List<String>

	fun prelaunchCommands(): List<String>

	companion object {

		fun fromId(id: String?) =
			when (id) {
				null -> null
				Pyp.id -> Pyp()
				MockPyp.id -> MockPyp()
				else -> throw IllegalArgumentException("unrecognized container id: $id")
			}

		const val canonicalConfigPath = "/var/pyp/config.toml"
	}

	class Pyp : Container {

		companion object {

			const val id = "pyp"

			val pypDir: Path =
				Paths.get("/opt/pyp")

			/**
			 * Returns the command-line invocation of the run version of the named command
			 */
			fun run(name: String): String =
				"\"${pypDir / "bin/run" / name}\""

			fun cmdWebrpc(vararg args: String): String =
				ArrayList<String>().apply {
					add("apptainer")
					add("--quiet")
					add("exec")
					add("--no-home")
					devBind?.let {
						add("--bind=\"$it\"")
					}
					add("\"${Config.instance.pyp.container}\"")
					add(run("webrpc"))
					addAll(args)
				}.joinToString(" ")

			val devBind: String? =
				Config.instance.pyp.sources?.let {
					"$it:/opt/pyp"
				}
		}

		override val sifPath = Config.instance.pyp.container
		override val binds = Config.instance.pyp.run {
			ArrayList<String>().apply {

				// add the config file bind
				add("${Config.hostPath()}:$canonicalConfigPath")

				// add the config binds
				for (bind in binds) {
					add(bind.toString())
				}

				// add the scratch bind
				add(scratch.toString())

				// add the pyp sources bind if needed
				devBind?.let { add(it) }
			}
		}

		override fun prelaunchCommands() = ArrayList<String>().apply {

			// make the scratch dir
			add("mkdir -p \"${Config.instance.pyp.scratch}\"")

			// forward environment variables to PYP
			add("export PYP_CONFIG=\"$canonicalConfigPath\"")

			// add library lookup paths if needed
			Config.instance.pyp.cudaLibs
				.takeIf { it.isNotEmpty() }
				?.let { paths ->
					val pathsList = paths.joinToString(":") { "\\\"$it\\\"" }
					add("export LD_LIBRARY_PATH=\"\$LD_LIBRARY_PATH:$pathsList\"")
				}
		}
	}

	/**
	 * A container definition to help speed up testing the website when it interacts with pyp
	 */
	class MockPyp : Container {

		companion object {

			const val id = "mock-pyp"

			const val exec = "/usr/bin/mock-pyp"

			private val configOrThrow: Config.Pyp.Mock get() =
				Config.instance.pyp.mock
					?: throw NoSuchElementException("missing pyp.mock config")

			fun cmdWebrpc(vararg args: String): String =
				ArrayList<String>().apply {
					add(configOrThrow.exec.toString())
					add("webrpc")
					addAll(args)
				}.joinToString(" ")
		}

		override val sifPath = configOrThrow.container

		override val binds = listOf(
			// add the pyp sources bind, so we can get the argument config file
			"${Config.instance.pyp.sources}:/opt/pyp",
			// and the mock pyp executable
			"${configOrThrow.exec}:${exec}"
		)

		override fun prelaunchCommands() = emptyList<String>() // nothing to do
	}
}
