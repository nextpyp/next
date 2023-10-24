package edu.duke.bartesaghi.micromon.cluster

import edu.duke.bartesaghi.micromon.Backend
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
				else -> throw IllegalArgumentException("unrecognized container id: $id")
			}

		const val canonicalConfigPath = "/var/pyp/config.toml"
	}

	class Pyp : Container {

		companion object {

			val id = "pyp"

			val pypDir: Path =
				Paths.get("/opt/pyp")

			/**
			 * Returns the command-line invocation of the run version of the named command
			 */
			fun run(name: String): String =
				"\"${pypDir / "bin/run" / name}\""

			fun cmdWebrpc(vararg args: String): String =
				ArrayList<String>().apply {
					add("singularity")
					add("--quiet")
					add("exec")
					add("--no-home")
					devBind?.let {
						add("--bind=\"$it\"")
					}
					add("\"${Backend.config.pyp.container}\"")
					add(run("webrpc"))
					addAll(args)
				}.joinToString(" ")

			val devBind: String? =
				Backend.config.pyp.sources?.let {
					"$it:/opt/pyp"
				}
		}

		override val sifPath = Backend.config.pyp.container
		override val binds = Backend.config.pyp.run {
			ArrayList<String>().apply {

				// add the config file bind
				add("${Config.actualPath()}:$canonicalConfigPath")

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
			add("mkdir -p \"${Backend.config.pyp.scratch}\"")

			// forward environment variables to PYP
			add("export PYP_CONFIG=\"$canonicalConfigPath\"")
		}
	}
}
