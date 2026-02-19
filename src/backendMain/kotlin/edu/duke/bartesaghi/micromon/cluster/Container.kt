package edu.duke.bartesaghi.micromon.cluster

import edu.duke.bartesaghi.micromon.Config
import edu.duke.bartesaghi.micromon.linux.Posix
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.div


sealed interface Container {

	val id: String
	val sifPath: Path
	val binds: List<String>

	fun prelaunchCommands(): List<String> = emptyList()


	companion object {

		fun fromId(id: String?): Container? =
			when (id) {
				null -> null
				Pyp.id -> Pyp
				MockPyp.id -> MockPyp
				else -> throw IllegalArgumentException("unrecognized container id: $id")
			}

		const val canonicalConfigPath = "/var/pyp/config.toml"

		val all: List<Container> = when (Config.instance.pyp.mock) {
			null -> listOf(
				Pyp
				// TODO: someday: more containers here?
			)
			else -> listOf(MockPyp)
		}

		fun launchCommand(container: Container): String {

			// build the args needed to run the apptainer container
			val args = mutableListOf(
				// don't automatically pull in random stuff from the user's home directory
				// this confuses the hell out of Python package lookups
				"--no-home"
			)

			// add container-specific binds
			for (path in container.binds) {
				args.add("--bind=$path")
			}

			// add binds needed for all containers
			args.add("--bind=${Config.instance.web.sharedDir}")
			if (Config.instance.web.sharedExecDir != Config.instance.web.sharedDir) {
				args.add("--bind=${Config.instance.web.sharedExecDir}")
			}

			return "apptainer --quiet exec ${args.joinToString(" ", transform=Posix::quote)}"
		}

		fun launchScript(): String {

			val defaultContainer = all.first()

			// make a val with the dollar sign, since it's hard to escape that character in """ strings
			val d = "$"

			val containerConfigs = all.map { container ->
				"""
					|
					|${container.id})
					|	${container.prelaunchCommands().joinToString("\n\t")}
					|	${launchCommand(container)} --pwd="${d}pwd_path" ${d}extra_apptainer_args "${container.sifPath}" "$@"
					|	;;
				""".trimMargin()
			}

			return """
				|#!/bin/sh
				|
				|show_help() {
				|	echo "nextpyp container launcher usage:"
				|	echo "  launcher [--id <id>] [--pwd <pwd_path>] [--nv] -- <cmd>"
				|	echo "where:"
				|	echo "  <id> is the id of the container"
				|	echo "    one of: [${all.joinToString(", ") { it.id }}]"
				|	echo "    If no id is given, \`${defaultContainer.id}\` is used."
				|	echo "  <pwd_path> is the working directory of the process to be launched."
				|	echo "             If no path is given, the path \`/tmp\` is used."
				|	echo "  --nv turns on apptainer's NVidia support."
				|	echo "  <cmd> is the command (including arguments) of the process to run inside the container."
				|}
				|
				|if [ "$#" = 0 ]; then
				|	show_help
				|	exit
				|fi
				|
				|# set defaults
				|id=${defaultContainer.id}
				|pwd_path=/tmp
				|use_nv=0
				|
				|# parse arguments
				|while :; do
				|	case ${d}1 in
				|	
				|		--help)
				|			show_help
				|			exit
				|			;;
				|		
				|		--id)
				|			if [ -n "${d}2" ]; then
				|				id=$2
				|				shift
				|			else
				|				echo "ERROR: missing <id>"
				|				exit 1
				|			fi
				|			;;
				|			
				|		--pwd)
				|			if [ -n "${d}2" ]; then
				|				pwd_path=$2
				|				shift
				|			else
				|				echo "ERROR: missing <pwd_path>"
				|				exit 1
				|			fi
				|			;;
				|			
				|		--nv)
				|			use_nv=1
				|			;;
				|
				|		--)
				|			shift
				|			break
				|			# end of options: cmd and args now in $@
				|			;;
				|			
				|		-?*)
				|			echo "ERROR: unknown option: ${d}1"
				|			exit 1
				|			;;
				|
				|		*)
				|			break
				|			;;
				|	esac
				|	shift
				|done
				|
				|# build apptainer arguments
				|extra_apptainer_args=
				|if [ "${d}use_nv" ]; then
				|	extra_apptainer_args="${d}extra_apptainer_args --nv"
				|fi
				|
				|# clear any previous apptainer binds, since we'll set the ones we want explicitly
				|# see: https://apptainer.org/docs/user/main/bind_paths_and_mounts.html#user-defined-bind-paths
				|APPTAINER_BIND=
				|SINGULARITY_BIND=
				|
				|# pick the container
				|case ${d}id in
				|	${containerConfigs.joinToString("\n\t")}
				|
				|	*)
				|		echo "ERROR: unrecognized container id: ${d}id"
				|		exit 1
				|		;;
				|esac
			""".trimMargin()
		}
	}

	class Pyp {
		companion object : Container {

			override val id = "pyp"
			override val sifPath = Config.instance.pyp.container

			val devBind: String? =
				Config.instance.pyp.sources?.let {
					"$it:/opt/pyp"
				}

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

					// and add extra binds to allow nesting
					// (ie, the host side of the asymmetrical binds)
					add(Config.hostPath())
					Config.instance.pyp.sources?.let { add("$it") }
				}
			}

			val pypDir: Path =
				Paths.get("/opt/pyp")

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
		}
	}

	// TODO: make it easy to describe new containers based on the config for the pyp container

	/**
	 * A container definition to help speed up testing the website when it interacts with pyp
	 */
	class MockPyp {
		companion object  : Container {

			override val id = "mock-pyp"
			override val sifPath = configOrThrow.container

			val exec = "/usr/bin/mock-pyp"

			override val binds = listOf(
				// add the pyp sources bind, so we can get the argument config file
				"${Config.instance.pyp.sources}:/opt/pyp",
				// and the mock pyp executable
				"${configOrThrow.exec}:${exec}",
				// extra binds needed for nesting container launches
				// (ie, the host side of the asymmetrical binds)
				"$sifPath",
				"${Config.instance.pyp.sources}",
				"${configOrThrow.exec}"
			)

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
	}
}
