package edu.duke.bartesaghi.micromon

import com.sun.management.UnixOperatingSystemMXBean
import edu.duke.bartesaghi.micromon.cluster.Commands
import edu.duke.bartesaghi.micromon.linux.countCudaGpus
import edu.duke.bartesaghi.micromon.services.AuthType
import org.tomlj.Toml
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.div
import kotlin.math.max


/**
 * Parses the PYP configuration file.
 * See /pyp/docs/config.rst for a description of the configuration standard.
 */
class Config(toml: String) {

	companion object {

		fun actualPath(): String =
			if (Testing.active) {
				"run/config.toml"
			} else {
				System.getenv("PYP_CONFIG_ACTUAL")
					?: throw NoSuchElementException("no actual pyp config path")
			}

		val canonicalPath: Path = Paths.get("/var/micromon/config.toml")

		fun fromCanon(): Config =
			Config(canonicalPath.readString())

		fun fromTest(): Config =
			Config(Paths.get(actualPath()).readString())
	}

	val dev: Boolean

	data class Pyp(
		val container: Path,
		val sources: Path?,
		val scratch: Path,
		val binds: List<Path>,
		val cudaLibs: List<Path>
	) {
		
		companion object {
			
			val bindsDenyList = listOf(
				"/",
				"/bin/**",
				"/dev/**",
				"/environment/**",
				"/etc/**",
				"/lib/**",
				"/lib64/**",
				"/opt/**",
				"/proc/**",
				"/root/**",
				"/run/**",
				"/sbin/**",
				"/scif/**",
				"/singularity/**",
				"/srv/**",
				"/sys/**",
				"/usr/**",
				"/var/**"
			)

			fun isDenied(path: Path): Boolean =
				bindsDenyList.any { denyPathStr ->
					if (denyPathStr.endsWith("/**")) {
						// if two stars, match any subdirectory
						val denyPath = Paths.get(denyPathStr.substring(0, denyPathStr.length - 3))
						path.startsWith(denyPath)
					} else {
						// otherwise, only an exact match
						val denyPath = Paths.get(denyPathStr)
						path == denyPath
					}
				}
		}

		init {
			// check the binds against the denylist
			val deniedBinds = binds.filter { isDenied(it) }
			if (deniedBinds.isNotEmpty()) {
				throw IllegalArgumentException("The following binds are not allowed:\n\t${deniedBinds.joinToString("\n\t")}")
			}
		}
	}
	val pyp: Pyp

	data class Slurm(
		val user: String,
		val host: String,
		val key: Path,
		val port: Int,
		val maxConnections: Int,
		val timeoutSeconds: Int,
		val path: Path,
		val queues: List<String>,
		val gpuQueues: List<String>
	) {

		val commandsConfig = Commands.Config()
		val sshPoolConfig = SshPoolConfig(user, host, key, port, maxConnections, timeoutSeconds)

		fun cmd(name: String): String =
			(path / name).toString()

		val cmdSbatch: String get() = cmd("sbatch")
		val cmdScancel: String get() = cmd("scancel")
		val cmdSqueue: String get() = cmd("squeue")
	}
	val slurm: Slurm?

	data class Standalone(
		val availableCpus: Int = defaultAvailableCpus,
		val availableMemoryGiB: Int = defaultAvailableMemoryGiB,
		val availableGpus: Int = defaultAvailableGpus
	) {

		val commandsConfig = Commands.Config()

		companion object {
			val defaultAvailableCpus = max(1, Runtime.getRuntime().availableProcessors() - 1)
			val defaultAvailableMemoryGiB = run {
				val os = ManagementFactory.getOperatingSystemMXBean() as UnixOperatingSystemMXBean
				// by default, only manage 80% of total memory
				val totalBytes = (os.totalPhysicalMemorySize*0.8).toLong()
				(totalBytes/1024/1024/1024).toInt()
			}
			val defaultAvailableGpus = countCudaGpus()
		}
	}
	val standalone: Standalone?

	data class Web(
		val host: String,
		val port: Int,
		val localDir: Path,
		val sharedDir: Path,
		val auth: AuthType,
		val webhost: String,
		val debug: Boolean,
		val cmdSendmail: String?,
		val filesystems: List<Filesystem>,
		val heapMiB: Int,
		val databaseGB: Double,
		val jmx: Boolean,
		val oomdump: Boolean,
		val workflowDirs: List<Path>,
		val demo: Boolean,
		val maxProjectsPerUser: Int?,
		val minPasswordLength: Int,
		val runasDir: Path
	) {

		data class Filesystem(
			val dir: Path,
			val name: String,
			val lowSpaceAlert: LowSpaceAlert?
		) {
			data class LowSpaceAlert(
				val GiB: Int,
				val intervalHrs: Int,
				val emailAddress: String
			)
		}
	}
	val web: Web

	init {

		// parse the TOML
		val doc = Toml.parse(toml)
		if (doc.hasErrors()) {
			throw TomlParseException("TOML parsing failure:\n${doc.errors().joinToString("\n")}")
		}

		dev = doc.getBoolean("dev") ?: false

		pyp = doc.getTableOrThrow("pyp").run {
			Pyp(
				container = getStringOrThrow("container").toPath(),
				sources = getString("sources")?.toPath(),
				scratch = getStringOrThrow("scratch").toPath(),
				binds = getArray("binds")?.run {
					indices.map { i ->
						getString(i).toPath()
					}
				} ?: emptyList(),
				cudaLibs = getArray("cudaLibs")?.run {
					indices.map { i ->
						getString(i).toPath()
					}
				} ?: emptyList()
			)
		}

		slurm = doc.getTable("slurm")?.run {
			Slurm(
				user = getString("user") ?: System.getProperty("user.name"),
				host = getStringOrThrow("host"),
				key = getString("key")?.toPath() ?: SshPoolConfig.defaultKeyPath,
				port = getInt("port") ?: SshPoolConfig.defaultPort,
				maxConnections = getInt("maxConnections") ?: SshPoolConfig.defaultPoolSize,
				timeoutSeconds = getInt("timeoutSeconds") ?: SshPoolConfig.defaultTimeoutSeconds,
				path = getString("path")?.toPath()
					?: Paths.get("/usr/bin"),
				queues = getArray("queues")?.run {
						indices.map { i -> getString(i) }
					} ?: emptyList(),
				gpuQueues = getArray("gpuQueues")?.run {
						indices.map { i -> getString(i) }
					} ?: emptyList()
			)
		}

		standalone = doc.getTable("standalone")?.run {
			Standalone(
				availableCpus = getInt("availableCpus") ?: Standalone.defaultAvailableCpus,
				availableMemoryGiB = getInt("availableMemoryGiB") ?: Standalone.defaultAvailableMemoryGiB,
				availableGpus = getInt("availableGpus") ?: Standalone.defaultAvailableGpus
			)
		}

		web = doc.getTableOrThrow("web").run {
			val host = getString("host") ?: "127.0.0.1"
			// NOTE: for security reasons, we should only bind to localhost by default, rather than all network iterfaces
			val port = getInt("port") ?: 8080
			val sharedDir = getStringOrThrow("sharedDir").toPath()
			Web(
				host = host,
				port = port,
				localDir = getStringOrThrow("localDir").toPath(),
				sharedDir = sharedDir,
				auth = AuthType[getString("auth")] ?: AuthType.Login,
				webhost = getString("webhost") ?: "http://$host:$port",
				debug = getBoolean("debug") ?: false,
				cmdSendmail = getString("sendmail"),
				filesystems = mapTables("web.filesystem") { table, pos ->
					Web.Filesystem(
						table.getStringOrThrow("path", pos).toPath(),
						table.getStringOrThrow("name", pos),
						table.getTable("lowSpaceAlert")?.run {
							val alertPos = table.inputPositionOf("lowSpaceAlert")
							Web.Filesystem.LowSpaceAlert(
								getIntOrThrow("gib", alertPos),
								getIntOrThrow("hrs", alertPos),
								getStringOrThrow("email", alertPos)
							)
						}
					)
				},
				heapMiB = getInt("heapMiB") ?: 2048,
				databaseGB = getIntoDouble("databaseGB") ?: 1.0,
				jmx = getBoolean("jmx") ?: false,
				oomdump = getBoolean("oomdump") ?: false,
				workflowDirs = getArray("workflowDirs")?.run {
					indices.map { i ->
						getString(i).toPath()
					}
				} ?: emptyList(),
				demo = getBoolean("demo") ?: false,
				maxProjectsPerUser = getInt("maxProjectsPerUser"),
				minPasswordLength = getInt("minPasswordLength") ?: 12,
				runasDir = getString("runasDir")
					?.toPath()
					?: (sharedDir / "runas")
			)
		}
	}

	fun initDirs() {
		web.localDir.createDirsIfNeeded()
		web.sharedDir.createDirsIfNeeded()
	}

	override fun toString() = StringBuilder().apply {
		val indent = "                    "
		append("""
			|[]
			|               dev: $dev
			|[pyp]
			|       conatainer:  ${pyp.container}
			|          sources:  ${pyp.sources}
			|          scratch:  ${pyp.scratch}
			|            binds:  ${pyp.binds.joinToString("\n$indent")}
			|         cudaLibs:  ${pyp.cudaLibs.joinToString("\n$indent")}
			|
		""".trimMargin())
		if (slurm != null) {
			append("""
				|[slurm]
				|             user:  ${slurm.user}
				|             host:  ${slurm.host}
				|              key:  ${slurm.key}
				|             port:  ${slurm.port}
				|  max connections:  ${slurm.maxConnections}
				|     conn timeout:  ${slurm.timeoutSeconds} s
				|             path:  ${slurm.path}
				|           queues:  ${slurm.queues}
				|       GPU queues:  ${slurm.gpuQueues}
				|
			""".trimMargin())
		} else {
			val standalone = standalone ?: Standalone()
			append("""
				|[standalone]
				|     available cpus:  ${standalone.availableCpus}
				|   available memory:  ${standalone.availableMemoryGiB} GiB
				|     available gpus:  ${standalone.availableGpus}
				|
			""".trimMargin())
		}
		append("""
			|[web]
			|             host:  ${web.host}
			|             port:  ${web.port}
			|        local dir:  ${web.localDir}
			|       shared dir:  ${web.sharedDir}
			|             auth:  ${web.auth}
			|          webhost:  ${web.webhost}
			|            debug:  ${web.debug}
			|     sendmail cmd:  ${web.cmdSendmail}
			|         heap MiB:  ${web.heapMiB}
			|      database GB:  ${web.databaseGB}
			|              JMX:  ${web.jmx}
			|   OOM heap dumps:  ${web.oomdump}
			|    workflow dirs:  ${web.workflowDirs.joinToString("\n$indent")}
			|        demo mode:  ${web.demo}
			|        runas dir:  ${web.runasDir}
			|
		""".trimMargin())
	}.toString()
}
