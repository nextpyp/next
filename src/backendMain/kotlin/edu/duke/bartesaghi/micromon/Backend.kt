package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.linux.hostprocessor.HostProcessor
import edu.duke.bartesaghi.micromon.linux.subprocess.UserSubprocesses
import edu.duke.bartesaghi.micromon.projects.ProjectEventListeners
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.fromToml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import kotlin.io.path.div


object Backend {

	val log = LoggerFactory.getLogger("Backend")

	/** the current working directory */
	val cwd = Paths.get(System.getProperty("user.dir"))

	init {
		log.info("MicroMon started!")
		log.info("CWD: $cwd")
	}

	// get the config
	val config = Config.instance

	init {
		// echo the configuration, useful for troubleshooting issues
		log.info("Configuration:\n$config")
	}

	// load the pyp args
	val pypArgs = try {
		Args.fromToml(Paths.get("/opt/micromon/pyp_config.toml").readString())
	} catch (t: Throwable) {
		throw Error("Failed to parse pyp_config.toml. Aborting startup.", t)
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
	 * use `Backend.scope.launch()` instead of eg `GlobalScope.launch()`
	 */
	val scope = CoroutineScope(Dispatchers.Default)

	val hostProcessor = HostProcessor(config.web.localDir / "host-processor")
	val userSubprocesses = UserSubprocesses()
}
