package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.linux.hostprocessor.HostProcessor
import edu.duke.bartesaghi.micromon.linux.userprocessor.UserProcessors
import edu.duke.bartesaghi.micromon.projects.ProjectEventListeners
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.MicromonArgs
import edu.duke.bartesaghi.micromon.pyp.MockPyp
import edu.duke.bartesaghi.micromon.pyp.fromToml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.slf4j.LoggerFactory
import java.nio.file.Paths


class Backend(config: Config, val pypArgs: Args) {

	companion object {

		val log = LoggerFactory.getLogger("Backend")


		// load the pyp args from the config file
		val pypArgsFromConfig = try {
			Args.fromToml(Paths.get("/opt/micromon/pyp_config.toml").readString())
		} catch (t: Throwable) {
			throw Error("Failed to parse pyp_config.toml. Aborting startup.", t)
		}


		private fun prod(): Backend {
			val config = Config.instance
			var pypArgs = pypArgsFromConfig
			if (config.pyp.mock != null) {
				pypArgs = MockPyp.combineArgs(pypArgs)
			}
			return Backend(config, pypArgs)
		}

		private var _instance: Backend? = null

		val instance: Backend get() =
			_instance ?: install(prod())

		fun install(backend: Backend): Backend {
			_instance = backend
			return backend
		}

		fun uninstall() {
			_instance = null
		}
	}

	val pypArgsWithMicromon get() =
		pypArgs.appendAll(MicromonArgs.slurmLaunch)


	/** the current working directory */
	val cwd = Paths.get(System.getProperty("user.dir"))

	init {
		log.info("MicroMon backend started!")
		log.info("CWD: $cwd")
	}

	init {
		// echo the configuration, useful for troubleshooting issues
		log.info("Configuration:\n$config")
	}

	fun init() {
		// dummy function just to make sure initializers get called
	}

	val email = Email(config.web.cmdSendmail)

	/** Notifies users connected by websockets about server events */
	val projectEventListeners = ProjectEventListeners()

	val filesystems = FilesystemMonitor(config.web.filesystems, email)

	/**
	 * coroutine scope tied to this Backend singleton instance
	 * use `Backend.instance.scope.launch()` instead of eg `GlobalScope.launch()`
	 */
	val scope = CoroutineScope(Dispatchers.Default)

	val hostProcessor = HostProcessor()
	val userProcessors = UserProcessors(hostProcessor)
}
