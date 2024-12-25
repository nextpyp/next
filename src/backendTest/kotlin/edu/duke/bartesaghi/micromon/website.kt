package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.jobs.jobInfo
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.ktor.client.features.websocket.*
import io.ktor.client.statement.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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


	fun argsToml(block: ArgValues.() -> Unit): ArgValuesToml =
		ArgValues(pypArgs.appendAll(MicromonArgs.slurmLaunch)).apply {
			block()
		}.toToml()
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

	suspend fun createProject(name: String? = null): ProjectData {

		@Suppress("NAME_SHADOWING")
		val name = name ?: "Project-${uniqueNumber()}"

		val project = services.rpc(IProjectsService::create, name)
		log.info("Created project: ${project.projectName}")

		return project
	}

	suspend fun <R> listenToProject(project: ProjectData, block: suspend (ws: DefaultClientWebSocketSession) -> R): R {
		return services.websocket(RealTimeServices.project) { ws ->

			ws.outgoing.sendMessage(RealTimeC2S.ListenToProject(
				getUserId(),
				project.projectId
			))

			block(ws)
		}
	}

	suspend fun <R> createProjectAndListen(name: String? = null, block: suspend (project: ProjectData, ws: DefaultClientWebSocketSession) -> R): R {
		val project = createProject(name)
		return listenToProject(project) { ws ->
			block(project, ws)
		}
	}

	suspend inline fun <reified S:Any, reified A, reified D, F:suspend S.(String, String, A) -> D> importBlock(project: ProjectData, f: F, args: A): D =
		services.rpc(f, getUserId(), project.projectId, args)

	suspend inline fun <reified S:Any, reified A, reified D, F:suspend S.(String, String, CommonJobData.DataId, A) -> D> addBlock(project: ProjectData, upstream: JobData, f: F, args: A): D =
		services.rpc(f, getUserId(), project.projectId, upstream.link(), args)

	suspend inline fun <reified S:Any, reified A, reified C, reified D, F:suspend S.(String, String, CommonJobData.DataId, A, C?) -> D> addBlock(project: ProjectData, upstream: JobData, f: F, args: A, copyArgs: C? = null): D =
		services.rpc(f, getUserId(), project.projectId, upstream.link(), args, copyArgs)

	suspend inline fun <reified D:JobData> getBlock(project: ProjectData, job: D): D =
		// this is a bit inefficient, but whatever ... we don't actually have a single job data getter in the API
		services.rpc(IProjectsService::getJobs, getUserId(), project.projectId)
			.map { JobData.deserialize(it) }
			.find { it.jobId == job.jobId }
			?.let { it as D }
			?: throw NoSuchElementException("no job with id=${job.jobId} in project with id=${project.projectId}")

	class ProjectRunResult(
		val clusterJobs: List<ClusterJob>,
		val jobs: List<JobData>
	) {

		inline fun <reified D:JobData> getJobData(job: D): D =
			jobs
				.find { it.jobId == job.jobId }
				?.let { it as D }
				?: throw NoSuchElementException("no job with id=${job.jobId} in project run result")
	}

	suspend fun runProject(project: ProjectData, inJobs: List<JobData>, ws: DefaultClientWebSocketSession, timeout: Duration = 30.seconds): ProjectRunResult {

		// start the project run
		services.rpc(IProjectsService::run, getUserId(), project.projectId, inJobs.map { it.jobId })
		log.info("runProject(${project.projectName}, jobs=${inJobs.map { it.name }}")

		// wait for the run to finish, but collect the interesting bits from progress messages
		val clusterJobIds = ArrayList<String>()
		val outJobs = ArrayList<JobData>()
		val finish = ws.incoming.waitForMessage<RealTimeS2C.ProjectRunFinish>(timeout) { otherMsg ->
			when (otherMsg) {

				is RealTimeS2C.ClusterJobSubmit -> {
					clusterJobIds.add(otherMsg.clusterJobId)
					log.info("\tCluster job submitted: id=${otherMsg.clusterJobId}")
				}

				is RealTimeS2C.ClusterJobEnd -> {
					log.info("\tCluster job ended: id=${otherMsg.clusterJobId}, status=${otherMsg.resultType}")

					if (otherMsg.resultType == ClusterJobResultType.Failure) {
						// job failed, show the log, if possible
						val output = ClusterJob.get(otherMsg.clusterJobId)
							?.getLog()
							?.result
							?.out
							?: "(no log)"
						log.error("""
							|Failed cluster job log:
							|  ${output.lines().joinToString("\n   ")}
						""".trimMargin())
					}
				}

				is RealTimeS2C.JobFinish -> {
					outJobs.add(otherMsg.job())
					log.info("\tJob finish: id=${otherMsg.jobId}, status=${otherMsg.status}")
				}

				else -> {
					// ignore other messages ... for now?
					log.info("\tProject message: ${otherMsg::class.simpleName}")
				}
			}
		} ?: throw Error("Timed out waiting $timeout for project run to finish")

		log.info("\trun finished: ${finish.status}")
		if (finish.status != RunStatus.Succeeded) {

			// gather the cluster job logs, if any
			val logs = clusterJobIds
				.associateWith { ClusterJob.get(it)?.getLog()?.result?.out }
				.entries
				.joinToString("\n") { (clusterJobId, out) -> "clusterJob=${clusterJobId}:\n$out" }
			throw Error("Project run failed, ${clusterJobIds.size} cluster job(s):\n$logs")
		}

		// lookup the cluster jobs
		return ProjectRunResult(
			clusterJobs = clusterJobIds.map { ClusterJob.getOrThrow(it) },
			outJobs
		)
	}

	suspend fun getParticles3d(ownerType: OwnerType, list: ParticlesList, datumId: String): Particles3DData {

		val httpResponse = services.post("/kv/particles/${ownerType.id}/${list.ownerId}/${datumId}/getParticles3D", list.name)
		if (httpResponse.status.value != 200) {
			throw Error("HTTP Error: ${httpResponse.status.value}")
		}

		return Json.decodeFromString<Particles3DData>(httpResponse.readText())
	}
}


fun JobData.link(outputData: NodeConfig.Data = jobInfo.config.singleOutputOrThrow()) =
	CommonJobData.DataId(jobId, outputData.id)
