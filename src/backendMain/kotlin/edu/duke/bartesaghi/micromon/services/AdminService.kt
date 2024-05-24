package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import com.sun.management.UnixOperatingSystemMXBean
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.auth
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.auth.generateLoginToken
import edu.duke.bartesaghi.micromon.auth.lookupName
import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.standalone.PseudoCluster
import edu.duke.bartesaghi.micromon.linux.userprocessor.UserProcessorException
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.projects.Project
import edu.duke.bartesaghi.micromon.pyp.Pyp
import io.ktor.application.*
import io.kvision.remote.ServiceException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.time.withTimeout
import java.lang.management.ManagementFactory
import java.lang.management.MemoryPoolMXBean
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit


actual class AdminService : IAdminService {

	@Inject
	lateinit var call: ApplicationCall

	private fun usersOnOrThrow(butAllowIfDev: Boolean = false) {

		// don't bother if auth is disabled
		if (!Backend.config.web.auth.hasUsers) {

			// but allow in dev mode if explicitly enabled
			if (butAllowIfDev && Backend.config.dev) {
				return
			}

			throw ServiceException("User authentication is disabled")
		}
	}

	override suspend fun getInfo(): AdminInfo = sanitizeExceptions {

		val user = call.auth()

		// don't need to authenticate for this one
		return AdminInfo(
			needsBootstrap = Backend.config.web.auth.hasUsers && Database.users.countUsers() <= 0,
			adminLoggedIn = user?.isAdmin ?: false,
			demoLoggedIn = user?.isDemo ?: false,
			authType = Backend.config.web.auth,
			dev = Backend.config.dev,
			clusterMode = Cluster.instance.clusterMode
		)
	}

	override suspend fun getUsers(): List<User> = sanitizeExceptions {

		// authenticate the user as an admin
		call.authOrThrow().adminOrThrow()

		return Database.users.getAllUsers()
	}

	override suspend fun createUser(user: User) = sanitizeExceptions {

		// authenticate the user as an admin
		call.authOrThrow().adminOrThrow()
		usersOnOrThrow(butAllowIfDev = true)

		// don't create duplicate users
		val oldUser = Database.users.getUser(user.id)
		if (oldUser != null) {
			throw ServiceException("User already exists")
		}

		Database.users.create(user)
	}

	override suspend fun editUser(user: User) = sanitizeExceptions {

		// authenticate the user as an admin
		val me = call.authOrThrow()
		me.adminOrThrow()
		usersOnOrThrow(butAllowIfDev = true)

		// make sure we can't remove our own admin permission
		if (me.id == user.id && User.Permission.Admin !in user.permissions) {
			throw ServiceException("can't de-admin yourself")
		}

		Database.users.edit(user)
	}

	override suspend fun deleteUser(userId: String) = sanitizeExceptions {

		// authenticate the user as an admin
		val me = call.authOrThrow()
		me.adminOrThrow()
		usersOnOrThrow(butAllowIfDev = true)

		// make sure we're not deleting our own account
		if (me.id == userId) {
			throw ServiceException("can't delete yourself")
		}

		Database.users.delete(userId)
	}

	override suspend fun deleteUserPassword(userId: String) = sanitizeExceptions {

		// authenticate the user as an admin
		val me = call.authOrThrow()
		me.adminOrThrow()
		usersOnOrThrow()

		// make sure we're not deleting our own password
		if (me.id == userId) {
			throw ServiceException("can't delete your own password")
		}

		Database.users.removePasswordHash(userId)
	}

	override suspend fun generateLoginLink(userId: String): String = sanitizeExceptions {

		// authenticate the user as an admin
		call.authOrThrow().adminOrThrow()
		usersOnOrThrow()

		// generate a secure login token
		val token = generateLoginToken(userId)

		return "/auth/token/${userId.urlEncode()}/${token.base62Encode()}"
	}

	override suspend fun pypRpcPing(): Option<PingResponse> = sanitizeExceptions {

		// authenticate the user as an admin
		val me = call.authOrThrow()
		me.adminOrThrow()

		// register a ping listener
		val listener = addPingListener(me, call)

		// make a unique id for this ping request
		// assuming we only get one request per user per millisecond
		val ownerId = "${me.id}/ping/${Instant.now().toEpochMilli()}"

		// launch a PYP job to send the ping
		Pyp.webrpc.launch(
			me.id,
			"ping",
			"ping",
			ownerId,
			Companion,
			Paths.get("/tmp"),
			args = listOf(
				"ping"
			)
		)

		return listener.waitForResponse(Duration.ofSeconds(30))
			.toOption()
	}

	override suspend fun getGroups(): List<Group> = sanitizeExceptions {

		// authenticate the user as an admin
		call.authOrThrow().adminOrThrow()

		return Database.groups.getAll()
	}

	override suspend fun createGroup(group: Group): Group = sanitizeExceptions {

		// authenticate the user as an admin
		call.authOrThrow().adminOrThrow()

		return Database.groups.create(group)
	}

	override suspend fun editGroup(group: Group) = sanitizeExceptions {

		// authenticate the user as an admin
		call.authOrThrow().adminOrThrow()

		// make sure the group exists
		val groupId = group.id
			?: return
		val oldGroup = Database.groups.get(groupId)
			?: return

		Database.groups.edit(group)

		// look for name changes
		if (oldGroup.name != group.name) {
			LinkTree.groupRenamed(oldGroup.name, group)
		}
	}

	override suspend fun deleteGroup(groupId: String) = sanitizeExceptions {

		// authenticate the user as an admin
		call.authOrThrow().adminOrThrow()

		val group = Database.groups.get(groupId)
			?: return

		Database.groups.delete(groupId)

		LinkTree.groupDeleted(group)
	}

	override suspend fun standaloneData(): Option<StandaloneData> = sanitizeExceptions {

		// authenticate the user as an admin
		call.authOrThrow().adminOrThrow()

		// are we even running in standalone mode?
		val cluster = Cluster.instance as? PseudoCluster
			?: return null.toOption()

		return cluster.state().toOption()
	}

	override suspend fun jobs(): JobsAdminData = sanitizeExceptions {

		// authenticate the user as an admin
		call.authOrThrow().adminOrThrow()

		// get all the projects currently running
		val projects = Database.projects.getAllRunningOrWaiting { docs ->
			docs
				.map { Project(it) }
				.toList()
		}

		// get all the project runs currently running or waiting
		val projectsAndRuns = projects.flatMap { project ->
			project.getRuns()
				.filter { it.status in listOf(RunStatus.Running, RunStatus.Waiting) }
				.map { project to it }
		}

		return JobsAdminData(
			projectRuns = projectsAndRuns.map { (project, projectRun) ->
				val projectRunData = projectRun.toData()
				ProjectRunAdminData(
					project.userId,
					User.lookupName(project.userId),
					project.projectId,
					project.name,
					projectRunData,
					clusterJobs = projectRunData.jobs.flatMap { jobRun ->
						jobRun.clusterJobs.map { clusterJob ->
							val log = ClusterJob.getLog(clusterJob.clusterJobId)
							ClusterJobAdminData(
								clusterJob.clusterJobId,
								clusterId = log?.launchResult?.jobId,
								launchResult = log?.launchResult?.toData(),
								history = log?.history
									?.map { historyEntry ->
										ClusterJobAdminData.HistoryEntry(
											historyEntry.status.name,
											historyEntry.timestamp
										)
									}
									?: emptyList()
							)
						}
					}
				)
			}
		)
	}

	override suspend fun perf(): PerfData = sanitizeExceptions {

		val bytesPerMiB = 1024L*1024L


		val os = ManagementFactory.getOperatingSystemMXBean() as UnixOperatingSystemMXBean
		val osinfo = PerfData.OSInfo(
			hostname = hostname ?: "(unknown)",
			mem = PerfData.MemPool.fromFree(
				os.freePhysicalMemorySize/bytesPerMiB,
				os.totalPhysicalMemorySize/bytesPerMiB
			),
			numCpus = os.availableProcessors,
			systemCpuLoad = os.systemCpuLoad,
			processCpuLoad = os.processCpuLoad,
			loadAvg = os.systemLoadAverage,
			openFileDescriptors = os.openFileDescriptorCount,
			maxFileDescriptors = os.maxFileDescriptorCount
		)

		// get basic JVM memory info
		val jvmMem = Runtime.getRuntime()
			.let {
				val freeMiB = it.freeMemory()/bytesPerMiB // NOTE: relative to total, not max
				val totalMiB = it.totalMemory()/bytesPerMiB
				val usedMiB = totalMiB - freeMiB
				val maxMiB = it.maxMemory()/bytesPerMiB
				PerfData.MemPool.fromUsed(usedMiB, totalMiB, maxMiB)
			}

		// get more detailed JVM memory info
		// the pools we care about on a v17 JVM are:
		//   `G1 Eden Space` - for brand new objects
		//   `G1 Survivor Space` - for not-quite-brand-new objects
		//   `G1 Old Gen` - for long-term storage
		fun MemoryPoolMXBean.memPoolOld() =
			usage.let {
				PerfData.MemPool.fromUsed(
					it.used/bytesPerMiB,
					it.committed/bytesPerMiB,
					it.max/bytesPerMiB
				)
			}
		fun MemoryPoolMXBean.memPoolYoung() =
			usage.let {
				// NOTE: young generations have max=0, so just ignore it
				PerfData.MemPool.fromUsed(
					it.used/bytesPerMiB,
					it.committed/bytesPerMiB
				)
			}
		operator fun List<MemoryPoolMXBean>.get(name: String) =
			find { it.name == name }
				?: throw NoSuchElementException("no memory pool named \"$name\"")
		val pools = ManagementFactory.getMemoryPoolMXBeans()
		val jvmHeapEden = pools["G1 Eden Space"].memPoolYoung()
		val jvmHeapSurvivor = pools["G1 Survivor Space"].memPoolYoung()
		val jvmHeapOld = pools["G1 Old Gen"].memPoolOld()

		// get request metrics
		val requests = call.application.feature(RequestMetrics).report()

		return PerfData(
			osinfo,
			jvmMem,
			jvmHeapEden,
			jvmHeapSurvivor,
			jvmHeapOld,
			requests
		)
	}

	override suspend fun checkUserProcessor(osUsername: String): UserProcessorCheck = sanitizeExceptions {

		val userProcessor = try {
			Backend.userProcessors.get(osUsername)
		} catch (ex: UserProcessorException) {
			return UserProcessorCheck.failure(ex.path.toString(), ex.problems)
		}

		val uids = try {
			userProcessor.uids()
		} catch (t: Throwable) {
			return UserProcessorCheck.failure(userProcessor.path.toString(), listOf(
				"Failed to query UIDs: ${t.message ?: "(no error message)"}"
			))
		}

		// look up the username, very carefully
		val username = try {
			Backend.hostProcessor.username(uids.euid)
		} catch (t: Throwable) {
			return UserProcessorCheck.failure(userProcessor.path.toString(), listOf(
				"Failed to lookup username from UID ${uids.euid}"
			))
		}
		if (username == null) {
			return UserProcessorCheck.failure(userProcessor.path.toString(), listOf(
				"Effective user (UID=${uids.euid}) has no username"
			))
		}

		return UserProcessorCheck.success(userProcessor.path.toString(), username)
	}


	companion object : ClusterJob.OwnerListener {

		override val id = "AdminService"

		private val pingListeners = HashMap<String,PingListener>()

		fun init() {
			ClusterJob.ownerListeners.add(this)
		}

		fun addPingListener(user: User, call: ApplicationCall): PingListener {
			synchronized(pingListeners) {

				if (user.id in pingListeners) {
					throw ServiceException("Already waiting for a ping")
				}

				return PingListener(user, call).apply {
					pingListeners[user.id] = this
				}
			}
		}

		fun removePingListener(listener: PingListener) {
			synchronized(pingListeners) {
				pingListeners.remove(listener.user.id)
			}
		}

		override suspend fun ended(ownerId: String, resultType: ClusterJobResultType) {

			// parse the owner id to get the listener
			val userId = ownerId.split("/").firstOrNull() ?: return
			val listener = pingListeners[userId] ?: return

			// get the job result from the log
			val job = ClusterJob.getByOwner(ownerId).firstOrNull() ?: return
			val result = job.getLog()?.result

			listener.finished(PingResponse(
				resultType,
				output = result?.out,
				exitCode = result?.exitCode
			))
		}

		val hostname: String? by lazy {

			// NOTE: all the simple tricks for getting the hostname in the dev environment don't seem to work
			// like the HOSTNAME envvar (empty), or the /etc/hostname file (localhost.localdomain)
			// so fall back to the linux `hostname` command

			val process = Runtime.getRuntime()
				.exec("hostname")
			process.waitFor(2, TimeUnit.SECONDS)
			process.inputStream
				.use { stdout ->
					stdout.bufferedReader().use { reader ->
						reader.readLine()
					}
				}
				.takeIf { it.isNotBlank() }
		}
	}
}


class PingListener(
	val user: User,
	val call: ApplicationCall
) {

	private val channel = Channel<PingResponse>()

	suspend fun waitForResponse(timeout: Duration): PingResponse? =
		try {
			withTimeout(timeout) {

				// wait for the result from the ClusterJob listener
				channel.receive()
			}
		} catch (ex: TimeoutCancellationException) {
			null
		} finally {
			remove()
		}

	suspend fun finished(result: PingResponse) {

		// send the result to the waiting coroutine
		channel.send(result)
	}

	fun remove() {
		AdminService.removePingListener(this)
	}
}
