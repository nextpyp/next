package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.Group
import edu.duke.bartesaghi.micromon.User
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


/**
 * Functions for administrators.
 */
@KVService
interface IAdminService {

	@KVBindingRoute("admin/info")
	suspend fun getInfo(): AdminInfo

	@KVBindingRoute("admin/users")
	suspend fun getUsers(): List<User>

	@KVBindingRoute("admin/user/create")
	suspend fun createUser(user: User)

	@KVBindingRoute("admin/user/edit")
	suspend fun editUser(user: User)

	@KVBindingRoute("admin/user/delete")
	suspend fun deleteUser(userId: String)

	@KVBindingRoute("admin/user/deletepw")
	suspend fun deleteUserPassword(userId: String)

	@KVBindingRoute("admin/user/genlink")
	suspend fun generateLoginLink(userId: String): String

	@KVBindingRoute("admin/pypRpcPing")
	suspend fun pypRpcPing(): Option<PingResponse>

	@KVBindingRoute("admin/groups")
	suspend fun getGroups(): List<Group>

	@KVBindingRoute("admin/group/create")
	suspend fun createGroup(group: Group): Group

	@KVBindingRoute("admin/group/edit")
	suspend fun editGroup(group: Group)

	@KVBindingRoute("admin/group/delete")
	suspend fun deleteGroup(groupId: String)

	@KVBindingRoute("admin/standalone/state")
	suspend fun standaloneData(): Option<StandaloneData>

	@KVBindingRoute("admin/jobs")
	suspend fun jobs(): JobsAdminData

	@KVBindingRoute("admin/perf")
	suspend fun perf(): PerfData

	@KVBindingRoute("admin/userProcessor/check")
	suspend fun checkUserProcessor(osUsername: String): UserProcessorCheck
}


@Serializable
enum class AuthType(val id: String, val hasUsers: Boolean) {

	None("none", false),
	Login("login", true),
	ReverseProxy("reverse-proxy", true);

	companion object {

		private fun String.normalize(): String =
			lowercase()

		operator fun get(id: String?): AuthType? =
			values().find { id?.normalize() == it.id.lowercase() }
	}
}

@Serializable
data class AdminInfo(
	val needsBootstrap: Boolean,
	val adminLoggedIn: Boolean,
	val demoLoggedIn: Boolean,
	val authType: AuthType,
	val dev: Boolean,
	val clusterMode: ClusterMode
)

@Serializable
enum class ClusterMode {
	SLURM,
	Standalone,
	LoadTesting
}

@Serializable
data class PingResponse(
	val resultType: ClusterJobResultType,
	val output: String?,
	val exitCode: Int?
)


@Serializable
data class StandaloneData(
	val resources: List<Resource>,
	val availableGpus: List<Int>,
	val jobs: List<Job>,
	val tasksRunning: List<TaskRef>,
	val tasksWaiting: List<TaskRef>
) {

	@Serializable
	data class Resource(
		val name: String,
		val available: Int,
		val total: Int
	) {

		val used: Int get() = total - available
	}

	@Serializable
	data class Job(
		val standaloneId: Long,
		val clusterId: String,
		val ownerId: String?,
		val name: String?,
		val resources: List<Pair<String,Int>>,
		val tasks: List<Task>,
		val waitingReason: String,
		val canceled: Boolean
	) {

		fun ref(task: Task): TaskRef =
			TaskRef(standaloneId, task.taskId)
	}

	@Serializable
	data class Task(
		val taskId: Int,
		val arrayId: Int?,
		val resources: Map<String,Int>,
		val reservedGpus: List<Int>,
		val pid: Long?,
		val waitingReason: String?,
		val finished: Boolean
	)

	@Serializable
	data class TaskRef(
		val jobId: Long,
		val taskId: Int
	)
}

@Serializable
data class JobsAdminData(
	val projectRuns: List<ProjectRunAdminData>
)

@Serializable
data class ProjectRunAdminData(
	val userId: String,
	val userName: String,
	val projectId: String,
	val projectName: String,
	val data: ProjectRunData,
	val clusterJobs: List<ClusterJobAdminData>
)

@Serializable
data class ClusterJobAdminData(
	val clusterJobId: String,
	val clusterId: Long?,
	val launchResult: ClusterJobLaunchResultData?,
	val history: List<HistoryEntry>
) {

	@Serializable
	data class HistoryEntry(
		val status: String,
		val timestamp: Long
	)
}


@Serializable
data class PerfData(
	val osinfo: OSInfo,
	val jvmMem: MemPool,
	val jvmHeapEden: MemPool,
	val jvmHeapSurvivor: MemPool,
	val jvmHeapOld: MemPool,
	val requests: RequestStats
) {

	@Serializable
	data class OSInfo(
		val hostname: String,
		val mem: MemPool,
		val numCpus: Int,
		val systemCpuLoad: Double,
		val processCpuLoad: Double,
		val loadAvg: Double,
		val openFileDescriptors: Long,
		val maxFileDescriptors: Long
	)

	@Serializable
	data class MemPool(
		val usedMiB: Long,
		val totalMiB: Long?,
		val maxMiB: Long
	) {

		val usedPercent: Double get() =
			usedMiB*100.0/maxMiB

		companion object {

			fun fromUsed(usedMiB: Long, totalMiB: Long, maxMiB: Long) =
				MemPool(usedMiB, totalMiB, maxMiB)

			fun fromUsed(usedMiB: Long, maxMiB: Long) =
				MemPool(usedMiB, null, maxMiB)

			fun fromFree(freeMiB: Long, maxMiB: Long) =
				MemPool(maxMiB - freeMiB, null, maxMiB)
		}
	}

	/**
	 * Statistics on requests from the last 30 seconds
	 */
	@Serializable
	data class RequestStats(
		val numRequests: Int,
		val requestsPerSecond: Double,
		val requestLatency: Latency?
	) {
		@Serializable
		data class Latency(
			val ms0pct: Long,
			val ms5pct: Long,
			val ms10pct: Long,
			val ms25pct: Long,
			val ms50pct: Long,
			val ms75pct: Long,
			val ms95pct: Long,
			val ms90pct: Long,
			val ms100pct: Long,
		)
	}
}


@Serializable
data class UserProcessorCheck(
	/** in the real filesystem, ie, outside the container */
	val path: String,
	val username: String?,
	val problems: List<String>?
) {

	companion object {

		fun failure(path: String, problems: List<String>) =
			UserProcessorCheck(path, null, problems)

		fun success(path: String, username: String) =
			UserProcessorCheck(path, username, null)
	}
}
