package edu.duke.bartesaghi.micromon

import io.kotest.common.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div


class EphemeralWebsite(configurator: Configurator.() -> Unit = {}): AutoCloseable {

	companion object {

		private val portRange = 8042 .. 8076
		private val ports = ConcurrentHashMap<Int,Boolean>()

		private val log = LoggerFactory.getLogger("EphemeralWebsite")
	}


	// reserve a port
	private val port = run {
		val numAttempts = 100
		for (i in 0 until numAttempts) {
			val port = portRange.random()
			if (!ports.contains(port)) {
				ports[port] = true
				return@run port
			}
		}
		throw Error("failed to reserve port after $numAttempts attempts: used ${ports.size} of ${portRange.last - portRange.first + 1} ports")
	}

	// create folders
	private val dir = Files.createTempDirectory("ephemeralWebsite-$port-")

	inner class Configurator {

		var auth: String = "none"

		// TODO: see what minimal config we need to make

		fun config(): Config =
			Config("""
				|
				|[pyp]
				|container = 'not used for these tests'
				|scratch = 'not used for these tests'
				|mock = { container = "/media/micromon/run/nextPYP.sif", exec = "/media/micromon/run/mock-pyp" }
				|
				|[web]
				|localDir = '${dir / "local"}'
				|sharedDir = '${dir / "sharedData"}'
				|sharedExecDir = '${dir / "sharedExec"}'
				|host = 'localhost'
				|port = $port
				|debug = true
				|auth = '$auth'
			""".trimMargin())
	}

	// parse the configuration
	val config = run {

		// build the config
		val cfgtor = Configurator()
		cfgtor.configurator()
		val config = cfgtor.config()

		// make the needed folders
		config.web.localDir.createDirsIfNeeded()
		config.web.sharedDir.createDirsIfNeeded()
		config.web.sharedExecDir.createDirsIfNeeded()

		config
	}


	inner class InstalledWebsite {

		val services = KVisionServices("http://localhost:$port")
	}

	fun <R> useInstalled(block: suspend (InstalledWebsite) -> R): R = use {
		Config.install(config)
		try {
			val webserver = startWebServer(config.web, false)
			try {
				runBlocking {
					block(InstalledWebsite())
				}
			} finally {
				webserver.stop(1000L, 5000L)
			}
		} finally {
			Config.uninstall()
		}
	}

	override fun close() {

		// cleanup the files
		try {
			dir.deleteDirRecursively()
		} catch (t: Throwable) {
			log.error("Failed to delete folder: $dir", t)
		}

		// release the port
		ports.remove(port)
	}
}
