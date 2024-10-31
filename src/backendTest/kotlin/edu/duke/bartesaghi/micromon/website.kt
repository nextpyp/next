package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.MockPyp
import edu.duke.bartesaghi.micromon.services.*
import io.ktor.client.features.websocket.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.div
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


class EphemeralConfig(configurator: Configurator.() -> Unit = {}) : AutoCloseable {

	companion object {

		private val log = LoggerFactory.getLogger("EphemeralConfig")

		private val portRange = 8042 .. 8076
		private val ports = ConcurrentHashMap<Int,Boolean>()
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

		var pypArgs: Args = Backend.pypArgsFromConfig
		var includeMocks: Boolean = true

		fun config(): Config =
			Config("""
				|
				|[pyp]
				|container = 'not used for these tests'
				|scratch = 'not used for these tests'
				|mock = { container = "/media/micromon/run/nextPYP.sif", exec = "/media/micromon/run/mock-pyp" }
				|sources = "/media/pyp"
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
	val config: Config
	val pypArgs: Args
	init {

		// build the config
		val cfgtor = Configurator()
		cfgtor.configurator()
		val config = cfgtor.config()

		// make the needed folders, like the installer would
		config.web.localDir.createDirsIfNeeded()
		config.web.sharedDir.createDirsIfNeeded()
		for (name in listOf("batch", "log", "users", "os-users", "sessions", "groups")) {
			(config.web.sharedDir / name).createDirsIfNeeded()
		}
		config.web.sharedExecDir.createDirsIfNeeded()
		for (name in listOf("user-processors")) {
			(config.web.sharedExecDir / name).createDirsIfNeeded()
		}

		this.config = config

		// build the pyp args
		var pypArgs = cfgtor.pypArgs
		if (cfgtor.includeMocks) {
			pypArgs = MockPyp.combineArgs(pypArgs)
		}

		this.pypArgs = pypArgs
	}


	inner class Installed : AutoCloseable {

		private val backend = Backend(config, pypArgs)

		init {
			Config.install(config)
			Backend.install(backend)
		}

		override fun close() {
			Backend.uninstall()
			Config.uninstall()
		}
	}
	fun install() = Installed()

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


class EphemeralWebsite: AutoCloseable {

	companion object {

		private val log = LoggerFactory.getLogger("EphemeralWebsite")

		private val uniqueCounter = AtomicLong(0)
		fun uniqueNumber(): Long =
			uniqueCounter.incrementAndGet()
	}

	init {
		log.info("Starting web server ...")
	}
	private val config = Config.instance
	private val webserver = startWebServer(config.web, false)


	val services = KVisionServices("localhost", config.web.port)


	override fun close() {

		log.info("Stopping web server ...")

		// stop the server
		webserver.stop(1000L, 5000L)

		log.info("Web server stopped")
	}


	fun getUserId(): String {
		// TODO: actually manage login into
		return User.NoAuthId
	}

	suspend fun <R> createProject(name: String? = null, block: suspend (project: ProjectData, ws: DefaultClientWebSocketSession) -> R): R {

		@Suppress("NAME_SHADOWING")
		val name = name ?: "Project-${uniqueNumber()}"

		val project = services.rpc(IProjectsService::create, name)
		log.info("Created project: ${project.projectName}")

		return services.websocket(RealTimeServices.project) { ws ->

			ws.outgoing.sendMessage(RealTimeC2S.ListenToProject(
				getUserId(),
				project.projectId
			))

			block(project, ws)
		}
	}

	suspend fun runProject(project: ProjectData, jobs: List<JobData>, ws: DefaultClientWebSocketSession, timeout: Duration = 30.seconds): List<ClusterJob> {

		// start the project run
		services.rpc(IProjectsService::run, getUserId(), project.projectId, jobs.map { it.jobId })
		log.info("runProject(${project.projectName}, jobs=${jobs.map { it.name }}")

		// wait for the run to finish, but collect the launched cluster jobs
		val clusterJobIds = ArrayList<String>()
		val finish = ws.incoming.waitForMessage<RealTimeS2C.ProjectRunFinish>(timeout) { otherMsg ->
			when (otherMsg) {

				is RealTimeS2C.ClusterJobSubmit -> {
					clusterJobIds.add(otherMsg.clusterJobId)
					log.info("\tCluster job submitted: id=${otherMsg.clusterJobId}")
				}

				is RealTimeS2C.ClusterJobEnd -> {
					log.info("\tCluster job ended: id=${otherMsg.clusterJobId}, status=${otherMsg.resultType}")
				}

				else -> {
					// ignore other messages ... for now?
					log.info("\tProject message: ${otherMsg::class.simpleName}")
				}
			}
		} ?: throw Error("Timed out waiting $timeout for project run to finish")

		log.info("\trun finished: ${finish.status}")
		if (finish.status != RunStatus.Succeeded) {
			throw Error("Project run failed")
		}

		// lookup the cluster jobs
		return clusterJobIds.map { ClusterJob.getOrThrow(it) }
	}
}
