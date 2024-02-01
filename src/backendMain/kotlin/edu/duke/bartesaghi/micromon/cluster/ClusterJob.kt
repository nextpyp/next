package edu.duke.bartesaghi.micromon.cluster

import com.mongodb.client.model.Updates.*
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.mongo.*
import edu.duke.bartesaghi.micromon.services.ClusterJobLaunchResultData
import edu.duke.bartesaghi.micromon.services.ClusterJobResultType
import edu.duke.bartesaghi.micromon.services.RunStatus
import kotlinx.coroutines.runBlocking
import org.bson.Document
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.NoSuchElementException
import kotlin.collections.ArrayList


class ClusterJob(
	val containerId: String?,
	val commands: Commands,
	/** working directory of the command. if a container was given, this path is inside the container */
	val dir: Path,
	val env: List<EnvVar> = emptyList(),
	val args: List<String> = emptyList(),
	val deps: List<String> = emptyList(),
	val ownerId: String? = null,
	val ownerListener: OwnerListener? = null,
	/** the name of the job to display on the website */
	val webName: String? = null,
	/** the name of the job to display in cluster management tools (eq `squeue`) */
	val clusterName: String? = null
) {

	fun submit(): String? = runBlocking {
		Cluster.submit(this@ClusterJob)
	}

	class LaunchFailedException(
		val reason: String,
		val console: List<String>,
		val command: String?
	) : IllegalArgumentException("$reason:\n${console.joinToString("\n")}")

	interface OwnerListener {
		val id: String
		suspend fun ended(ownerId: String, resultType: ClusterJobResultType)
	}

	/** the id in the database */
	var id: String? = null
		private set

	val idOrThrow: String get() =
		id ?: throw NoSuchElementException("sbatch job has no database id")

	enum class Status(val id: String, val oldIds: List<String>? = null) {

		/** submitted to Micromon */
		Submitted("submitted"),

		/** submitted to the cluster */
		Launched("launched"),

		/** running on the cluster */
		Started("started"),

		/** a cancel was requested in Micromon */
		Canceling("canceling"),

		/** don't know what's going on anymore, so we just stopped tracking this job */
		Abandoned("abandoned"),

		/** the cluster job has ended, for any reason */
		Ended("ended", listOf("finished"));

		companion object {
			operator fun get(id: String) =
				values().find { it.id == id || it.oldIds?.contains(id) == true }
					?: throw NoSuchElementException("unknown status: $id")
		}
	}

	data class ArrayProgress(
		val numStarted: Int,
		val numEnded: Int,
		/** a subset of numEnded */
		val numCanceled: Int,
		/** a subset of numEnded */
		val numFailed: Int
	) {

		fun toDoc() = Document().apply {
			set("started", numStarted)
			set("ended", numEnded)
			set("canceled", numCanceled)
			set("failed", numFailed)
		}

		companion object {

			fun fromDoc(doc: Document) = ArrayProgress(
				numStarted = doc.getInteger("started", 0),
				// NOTE: for backwards compatibility, also check the old "finished" entry
				numEnded = doc.getInteger("ended") ?: doc.getInteger("finished") ?: 0,
				numCanceled = doc.getInteger("canceled", 0),
				numFailed = doc.getInteger("failed", 0)
			)
		}
	}

	data class Log(
		val clusterJobId: String,
		val arrayId: Int? = null,
		var launchResult: LaunchResult? = null,
		var arrayProgress: ArrayProgress? = null,
		val history: MutableList<HistoryEntry> = ArrayList(),
		var result: Result? = null,
		val failures: MutableList<FailureEntry> = ArrayList()
	) {

		fun toDoc() = Document().apply {
			launchResult?.let { set("sbatch", it.toDoc()) }
			arrayProgress?.let { set("arrayProgress", it.toDoc()) }
			set("history", history.toDBList())
			result?.let { set("result", it.toDoc()) }
			set("failures", failures.map { it.toDoc() })
		}

		companion object {

			fun initDoc(doc: Document, entry: HistoryEntry) {
				doc["history"] = listOf(entry).toDBList()
			}

			fun fromDoc(doc: Document): Log {
				val (sbatchId, arrayId) = ClusterJobs.Log.parseId(doc.getString("_id"))
				return Log(
					sbatchId,
					arrayId,
					launchResult = doc.getDocument("sbatch")?.let { LaunchResult.fromDoc(it) },
					arrayProgress = doc.getDocument("arrayProgress")?.let { ArrayProgress.fromDoc(it) },
					history = ArrayList<HistoryEntry>().apply {
						doc.getList("history", List::class.java)
							?.forEach { add(HistoryEntry.fromDBList(it)) }
					},
					result = doc.getDocument("result")?.let { Result.fromDoc(it) },
					failures = ArrayList<FailureEntry>().apply {
						doc.getListOfDocuments("failures")
							?.forEach { add(FailureEntry.fromDoc(it)) }
					}
				)
			}
		}

		fun wasCanceled(): Boolean =
			history.any { it.status == Status.Canceling }

		fun status(): Status? =
			history.lastOrNull()?.status

		/**
		 * Picks a run status based on the job log.
		 */
		fun runStatus(): RunStatus =
			when (status()) {
				Status.Ended -> when (result?.type) {
					ClusterJobResultType.Success -> RunStatus.Succeeded
					ClusterJobResultType.Failure -> RunStatus.Failed
					ClusterJobResultType.Canceled -> RunStatus.Canceled
					null -> RunStatus.Failed
				}
				Status.Abandoned -> RunStatus.Failed
				Status.Canceling -> RunStatus.Canceled
				Status.Started -> RunStatus.Running
				Status.Launched -> RunStatus.Waiting
				Status.Submitted -> RunStatus.Waiting
				null -> RunStatus.Waiting
			}
	}

	data class HistoryEntry(
		val status: Status,
		val timestamp: Long = Instant.now().toEpochMilli()
	) {
		fun toDBList(): List<Any?> =
			listOf(status.id, timestamp)

		companion object {
			fun fromDBList(list: List<Any?>) =
				HistoryEntry(
					status = Status[list[0] as String],
					timestamp = list[1] as Long
				)
		}
	}

	data class FailureEntry(
		val timestamp: Instant = Instant.now()
	) {
		fun toDoc() = Document().apply {
			set("timestamp", timestamp.toEpochMilli())
		}

		companion object {

			fun fromDoc(doc: Document) = FailureEntry(
				timestamp = Instant.ofEpochMilli(doc.getLong("timestamp"))
			)
		}
	}

	data class LaunchResult(
		/** the id the cluster uses for the job */
		val jobId: Long?,
		/** the console output from the cluster launcher, eg sbatch */
		val out: String,
		/** the submission command, if any */
		val command: String?
	) {

		fun toDoc() = Document().apply {
			set("id", jobId)
			set("out", out)
			set("command", command)
		}

		companion object {

			fun fromDoc(doc: Document) = LaunchResult(
				jobId = doc.getLong("id"),
				out = doc.getString("out"),
				command = doc.getString("command")
			)
		}

		fun toData() = ClusterJobLaunchResultData(
			command,
			out,
			success = jobId != null
		)
	}

	fun create(): String {
		// create a database record of the submission
		val dbid = Database.cluster.launches.create {
			set("container", containerId)
			set("commands", commands.toDoc())
			set("dir", dir.toString())
			set("env", env.map { it.toList() })
			set("args", args)
			set("deps", deps)
			set("owner", ownerId)
			set("name", webName)
			set("clusterName", clusterName)
			set("listener", ownerListener?.id)
		}
		this.id = dbid
		return dbid
	}

	fun getLog(arrayId: Int? = null): Log? =
		getLog(idOrThrow, arrayId)

	fun getLogOrThrow(arrayId: Int? = null): Log =
		getLog(arrayId) ?: throw NoSuchElementException("no log for SLURM job $id")

	fun pushHistory(status: Status, arrayId: Int? = null) {
		Database.cluster.log.update(idOrThrow, arrayId,
			push("history", HistoryEntry(status).toDBList())
		)
	}

	fun pushFailure(entry: FailureEntry, arrayId: Int? = null) {
		Database.cluster.log.update(idOrThrow, arrayId,
			push("failures", entry.toDoc())
		)
	}

	data class Result(
		val type: ClusterJobResultType,
		val out: String?,
		val cancelReason: String? = null,
		val exitCode: Int? = null
	) {

		fun toDoc() = Document().apply {
			set("type", type.id)
			set("out", out)
			cancelReason?.let { set("cancelReason", it) }
			exitCode?.let { set("exitCode", it) }
		}

		companion object {

			fun fromDoc(doc: Document) = Result(
				type = ClusterJobResultType[doc.getString("type")],
				out = doc.getString("out"),
				cancelReason = doc.getString("cancelReason"),
				exitCode = doc.getInteger("exitCode")
			)

			/** non-zero exit codes indicate failure */
			fun isSuccess(exitCode: Int): Boolean =
				exitCode == 0
		}

		fun applyExitCode(exitCode: Int?): Result {

			// no exit code? no changes
			if (exitCode == null) {
				return this
			}

			// if the exit code indicates a failure, update the result type
			if (type == ClusterJobResultType.Success && !isSuccess(exitCode)) {
				return copy(type = ClusterJobResultType.Failure, exitCode = exitCode)
			}

			// otherwise, just add the exit code with no other changes
			return copy(exitCode = exitCode)
		}

		fun applyFailures(clusterJob: ClusterJob, arrayId: Int?): Result {

			val log = clusterJob.getLog(arrayId)
				?: return this

			return if (log.failures.isNotEmpty()) {
				copy(type = ClusterJobResultType.Failure)
			} else {
				this
			}
		}
	}


	interface Listener {
		suspend fun onSubmit(clusterJob: ClusterJob) {}
		suspend fun onStart(ownerId: String?, dbid: String) {}
		suspend fun onStartArray(ownerId: String?, dbid: String, arrayId: Int, numStarted: Int) {}
		suspend fun onEndArray(ownerId: String?, dbid: String, arrayId: Int, numEnded: Int, numCanceled: Int, numFailed: Int) {}
		suspend fun onEnd(ownerId: String?, dbid: String, resultType: ClusterJobResultType) {}
	}

	data class EnvVar(
		val name: String,
		val value: String
	) {

		fun toList() = listOf(name, value)
	}


	fun batchPath(): Path =
		batchDir.resolve("batch-$idOrThrow.sh")

	/**
	 * Get the path to the output file.
	 * Useful for reading cluster job output.
	 */
	fun outPath(arrayIndex: Int? = null): Path =
		if (commands.isArray) {
			if (arrayIndex == null) {
				throw IllegalArgumentException("array job requires array index")
			}
			logDir.resolve("out-$idOrThrow.$arrayIndex.log")
		} else {
			logDir.resolve("out-$idOrThrow.log")
		}

	/**
	 * Create a path to describe where the output file should be written.
	 * Useful for launching cluster jobs.
	 */
	fun outPathMask(): Path =
		if (commands.isArray) {
			logDir.resolve("out-$idOrThrow.%a.log")
		} else {
			logDir.resolve("out-$idOrThrow.log")
		}

	fun delete() {

		// delete the database entries
		Database.cluster.log.delete(idOrThrow)
		commands.arraySize?.let { arraySize ->
			for (i in 0 until arraySize) {
				Database.cluster.log.delete(idOrThrow, i)
			}
		}
		Database.cluster.launches.delete(idOrThrow)

		// wipe the id
		id = null
	}


	companion object {

		fun get(dbId: String): ClusterJob? =
			fromDoc(Database.cluster.launches.get(dbId))

		fun getByOwner(ownerId: String): List<ClusterJob> =
			Database.cluster.launches.getByOwner(ownerId)
				.mapNotNull { fromDoc(it) }

		fun find(
			ownerId: String? = null,
			clusterName: String? = null,
			notClusterName: List<String>? = null
		): List<ClusterJob> =
			Database.cluster.launches.find(ownerId, clusterName, notClusterName)
				.mapNotNull { fromDoc(it) }

		fun fromDoc(doc: Document?): ClusterJob? {
			if (doc == null) {
				return null
			}
			return ClusterJob(
				containerId = doc.getString("container"),
				commands = when (val c = doc.get("commands")) {
					is Document -> Commands.fromDoc(c)
					is List<*> -> Commands.fromList(c, doc.getInteger("arraySize"))
					else -> throw IllegalArgumentException("job has no commands")
				},
				dir = Paths.get(doc.getString("dir")),
				env = doc.getList("env", List::class.java)
					?.map { EnvVar(it[0] as String, it[1] as String) }
					?: emptyList(),
				args = doc.getListOfStrings("args") ?: emptyList(),
				deps = doc.getListOfStrings("deps") ?: emptyList(),
				ownerId = doc.getString("owner"),
				// older versions of the listener field were Long values
				// if we see any old long values, just ignore them
				ownerListener = ownerListeners.find(doc["listener"] as? String),
				webName = doc.getString("name"),
				clusterName = doc.getString("clusterName")
			).apply {
				id = doc.getObjectId("_id").toStringId()
			}
		}

		fun getLog(clusterJobId: String, arrayId: Int? = null): Log? =
			Database.cluster.log.get(clusterJobId, arrayId)
				?.let { Log.fromDoc(it) }

		private fun List<HistoryEntry>.toDBList() =
			map { it.toDBList() }

		// NOTE: listeners get added and iterated over by multiple threads,
		// so we need to use a synchronized hash map implementation here
		private val listeners = ConcurrentHashMap<Long,Listener>()

		fun listeners(): Collection<Listener> =
			listeners.values

		/**
		 * Listen in on all the ClusterLog traffic, for curiosity's sake.
		 * It's not crucial that any particular event is received by anyone in particular.
		 */
		fun addListener(listener: Listener): Long {
			val id = listeners.uniqueKey()
			listeners[id] = listener
			return id
		}

		fun removeListener(id: Long) {
			listeners.remove(id)
		}

		class OwnerListeners {

			private val listeners = ArrayList<OwnerListener>()

			fun add(listener: OwnerListener) {

				// don't add it more than once
				if (listeners.any { it === listener }) {
					return
				}

				listeners.add(listener)
			}

			fun find(id: String?) =
				listeners.find { it.id == id }
		}
		val ownerListeners = OwnerListeners()

		private val sharedDir = Backend.config.web.sharedDir
		private val batchDir = sharedDir.resolve("batch")
		private val logDir = sharedDir.resolve("log")
	}
}
