package edu.duke.bartesaghi.micromon.cluster

import com.mongodb.client.model.Updates.*
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.slurm.Template
import edu.duke.bartesaghi.micromon.linux.EnvVar
import edu.duke.bartesaghi.micromon.linux.Posix
import edu.duke.bartesaghi.micromon.mongo.*
import edu.duke.bartesaghi.micromon.services.ClusterJobLaunchResultData
import edu.duke.bartesaghi.micromon.services.ClusterJobLog
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
	/** run the job as the specified OS user, if any */
	val osUsername: String?,
	/** user-specific properties for the cluster template, if needed */
	val userProperties: Map<String,String>? = null,
	val containerId: String?,
	val commands: Commands,
	/** working directory of the command. if a container was given, this path is inside the container */
	val dir: Path,
	val env: List<EnvVar> = emptyList(),
	/**
	 * Free sbatch arguments intended for direct inclusion in a shell command.
	 * These arguments are sanitized before passing them to the shell.
	 */
	val args: List<String> = emptyList(),
	val deps: List<String> = emptyList(),
	val ownerId: String? = null,
	val ownerListener: OwnerListener? = null,
	/** the name of the job to display on the website */
	val webName: String? = null,
	/** the name of the job to display in cluster management tools (eq `squeue`) */
	val clusterName: String? = null,
	/** the type of the job, if any */
	val type: String? = null,
	/** the path of the template to use for this job */
	val template: String? = null
) {

	/**
	 * The cluster job arguments, but tokenized like a POSIX shell would handle them, and then parsed into a key=value map
	 * Useful when you want to read/use the arguments before sending them to a shell, or some other non-shell place
	 */
	val argsParsed: List<Pair<String?,String>> get() =
		args.flatMap { Posix.tokenize(it) }
			.map { arg ->
				val pos = arg.indexOfFirst { it == '=' }
					.takeIf { it >= 0 }
				if (pos != null) {
					val name = arg.substring(0 until pos)
						.trimStart('-')
					val value = arg.substring(pos + 1)
					name to value
				} else {
					null to arg
				}
			}

	fun submit(): String? = runBlocking {
		Cluster.submit(this@ClusterJob)
	}

	class LaunchFailedException(
		val reason: String,
		val console: List<String>,
		val command: String?
	) : IllegalArgumentException("""
		|reason: $reason
		|console:
		|    ${console.joinToString("\n    ")}
		|command: $command
	""".trimMargin())

	class ValidationFailedException(
		val reason: String
	) : IllegalArgumentException(reason) {

		constructor(t: Throwable) : this(t.message ?: "(unknown reason")
	}

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
		var submitFailure: String? = null,
		val launchScript: String? = null,
		var launchResult: LaunchResult? = null,
		var arrayProgress: ArrayProgress? = null,
		val history: MutableList<HistoryEntry> = ArrayList(),
		var result: Result? = null,
		val failures: MutableList<FailureEntry> = ArrayList()
	) {

		companion object {

			fun initDoc(doc: Document, entry: HistoryEntry) {
				doc["history"] = listOf(entry).toDBList()
			}

			fun fromDoc(doc: Document): Log {
				val (sbatchId, arrayId) = ClusterJobs.Log.parseId(doc.getString("_id"))
				return Log(
					sbatchId,
					arrayId,
					submitFailure = doc.getString("submitFailure"),
					launchScript = doc.getString("launchScript"),
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
				command = doc.getString("command"),
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
		val dbid = Database.instance.cluster.launches.create {
			set("osUsername", osUsername)
			set("userProperties", userProperties)
			set("container", containerId)
			set("commands", commands.toDoc())
			set("dir", dir.toString())
			set("env", env.map { it.toList() })
			set("args", args)
			set("deps", deps)
			set("owner", ownerId)
			set("name", webName)
			set("clusterName", clusterName)
			set("type", type)
			set("template", template)
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
		Database.instance.cluster.log.update(idOrThrow, arrayId,
			push("history", HistoryEntry(status).toDBList())
		)
	}

	fun pushFailure(entry: FailureEntry, arrayId: Int? = null) {
		Database.instance.cluster.log.update(idOrThrow, arrayId,
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
		Database.instance.cluster.log.delete(idOrThrow)
		commands.arraySize?.let { arraySize ->
			for (i in 0 until arraySize) {
				Database.instance.cluster.log.delete(idOrThrow, i)
			}
		}
		Database.instance.cluster.launches.delete(idOrThrow)

		// wipe the id
		id = null
	}

	data class Dependency(
		val dependencyId: String,
		val launchId: String?
	) {

		val launchIdOrThrow: String get() =
			launchId
				?: throw NoSuchElementException("dependency job with id=$dependencyId not launched yet")
	}

	/**
	 * Resolve the job's dependency ids (that refer to micromon targets)
	 * into launch ids (that refer to cluster scheduler (eg SLURM) targets).
	 *
	 * A resolved id is only returned if the job has already been launched.
	 * Unlaunched jobs will have null resolved ids.
	 */
	fun dependencies(): List<Dependency> =
		deps.map { depId ->

			// Sometimes, pyp puts `_N` on the end of the dependency id to signal a dependency
			// on the Nth element in an array job. The database doesn't know anything about that,
			// so take it off before jobId resolution.
			val (depIdJob, depIdArray) = depId
				.split('_')
				.let { it[0] to it.getOrNull(1) }

			var launchId = Database.instance.cluster.log.get(depIdJob)
				?.let { Log.fromDoc(it) }
				?.launchResult
				?.jobId
				?.toString()

			// But then put the `_N` back on before giving the IDs to the cluster.
			if (depIdArray != null) {
				launchId = "${launchId}_$depIdArray"
			}

			Dependency(depId, launchId)
		}

	fun templateName(): String? =
		try {
			Config.instance.slurm?.let { config ->
				template?.let {
					Template.Key(config, it)
						.toTemplateOrThrow()
						.readData()
						.title
				}
			}
		} catch (t: Throwable) {
			// can't read the template metadata, so use the path
			template
		}

	fun toData(): ClusterJobLog {

		// load the main log
		val log = getLog()

		return ClusterJobLog(
			representativeCommand = commands.representativeCommand(),
			commandParams = commands.params(),
			submitFailure = log?.submitFailure,
			template = templateName(),
			launchScript = log?.launchScript,
			launchResult = log?.launchResult?.toData(),
			resultType = log?.result?.type,
			exitCode = log?.result?.exitCode,
			log = log?.result?.out?.collapseProgress(),
			arraySize = commands.arraySize,
			failedArrayIds = Database.instance.cluster.log.findArrayIdsByResultType(idOrThrow, ClusterJobResultType.Failure)
				.sorted()
		)
	}


	companion object {

		fun get(dbId: String): ClusterJob? =
			fromDoc(Database.instance.cluster.launches.get(dbId))

		fun getOrThrow(dbId: String): ClusterJob =
			get(dbId)
				?: throw NoSuchElementException("No cluster job found with id=$dbId")

		fun getByOwner(ownerId: String): List<ClusterJob> =
			Database.instance.cluster.launches.getByOwner(ownerId)
				.mapNotNull { fromDoc(it) }

		fun find(
			ownerId: String? = null,
			clusterName: String? = null,
			notClusterName: List<String>? = null
		): List<ClusterJob> =
			Database.instance.cluster.launches.find(ownerId, clusterName, notClusterName)
				.mapNotNull { fromDoc(it) }

		fun fromDoc(doc: Document?): ClusterJob? {
			if (doc == null) {
				return null
			}
			return ClusterJob(
				osUsername = doc.getString("osUsername"),
				userProperties = doc.getMap<String>("userProperties"),
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
				clusterName = doc.getString("clusterName"),
				type = doc.getString("type"),
				template = doc.getString("template")
			).apply {
				id = doc.getObjectId("_id").toStringId()
			}
		}

		fun getLog(clusterJobId: String, arrayId: Int? = null): Log? =
			Database.instance.cluster.log.get(clusterJobId, arrayId)
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

		/**
		 * This dir should be created by the install process.
		 * We should never need to create it from the website process.
		 */
		val batchDir get() = Config.instance.web.sharedDir.resolve("batch")

		/**
		 * This dir should be created by the install process.
		 * We should never need to create it from the website process.
		 */
		val logDir get() = Config.instance.web.sharedDir.resolve("log")
	}
}
