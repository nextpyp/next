package edu.duke.bartesaghi.micromon.cluster

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.ClusterJobResultType
import edu.duke.bartesaghi.micromon.services.ClusterMode
import edu.duke.bartesaghi.micromon.services.ClusterQueues
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit


/**
 * Not a real cluster implementation.
 * Only useful because it can start and finish no-op jobs much faster than SLURM can,
 * so useful for load-testing the website cluster job event handling.
 */
class LoadTestingCluster : Cluster {

	override val clusterMode = ClusterMode.LoadTesting

	override val commandsConfig: Commands.Config get() =
		Commands.Config()

	override val queues: ClusterQueues =
		ClusterQueues(emptyList(), emptyList())

	private val sshPool = SSHPool(Backend.config.slurm!!.sshPoolConfig)

	override fun validate(job: ClusterJob) {
		// whatever, it's fine
	}

	override fun validateDependency(depId: String) {
		// whatever, it's fine
	}

	override suspend fun makeFoldersAndWriteFiles(folders: List<Path>, files: List<Cluster.FileInfo>) {

		// short circuit, just in case
		if (folders.isEmpty() && files.isEmpty()) {
			return
		}

		sshPool.connection {

			// make folders
			for (path in folders) {
				mkdirs(path)
			}

			// upload files
			sftp {
				for (file in files) {
					uploadText(file.path, file.text)
				}
			}

			// set executable
			for (file in files) {
				if (file.executable) {
					makeExecutable(file.path)
				}
			}
		}
	}

	override suspend fun launch(clusterJob: ClusterJob, depIds: List<String>, scriptPath: Path): ClusterJob.LaunchResult {

		val clusterJobId = clusterJob.idOrThrow

		val arrayIds: List<Int?> =
			clusterJob.commands.arraySize
				?.let { 1 .. it }
				?.toList()
				?: listOf()

		// "start" all the jobs
		for (i in arrayIds) {
			Cluster.started(clusterJobId, i)
		}

		suspend fun post(path: String, json: String): HttpResponse =
			HttpClient()
				.request<HttpStatement>("http://localhost:8080$path") {
					method = HttpMethod.Post
					body = json
				}
				.execute()

		suspend fun rpc(method: String, paramsJson: String) {
			val startNs = System.nanoTime()
			val response = post("/pyp", """
				|{
				|	"id": 0,
				|	"token": "${JsonRpc.token}",
				|	"method": "$method",
				|	"params": $paramsJson
				|}
			""".trimMargin())
			val elapsedS = (System.nanoTime() - startNs)/1_000_000_000.0
			println("rpc $method response in %.2f s: ${response.status} ${response.readText()}".format(elapsedS))
		}

		val pool = ThreadPoolExecutor(
			16,
			16,
			60, TimeUnit.SECONDS,
			LinkedBlockingQueue()
		)
		for (i in arrayIds) {
			pool.execute {
				runBlocking {

					// "run" the job
					val outPath = clusterJob.outPath(i)
					outPath.writeString("command result here")

					rpc("slurm_ended", """
						|{
						|	"webid": "$clusterJobId",
						|	"arrayid": $i,
						|	"exit_code": 0
						|}
					""".trimMargin())
				}
			}
		}

		return ClusterJob.LaunchResult(null, "")
	}

	override suspend fun waitingReason(launchResult: ClusterJob.LaunchResult) =
		"no one cares"

	override suspend fun cancel(clusterJobs: List<ClusterJob>) {
		// nope
	}

	override suspend fun jobResult(clusterJob: ClusterJob, arrayIndex: Int?): ClusterJob.Result {

		// read the job output and cleanup the log file
		val outPath = clusterJob.outPath(arrayIndex)
		val out: String? = try {
			sshPool.connection {
				sftp {
					val out = downloadText(outPath)
					delete(outPath)
					out
				}
			}
		} catch (ex: NoRemoteFileException) {
			// the remote log didn't exist... probably the process just didn't write to stdout or stderr?
			// or maybe there's some kind of network delay and we just can't see the log file yet?
			// TODO: wait a bit and try to download the log file again?
			null
		}

		// determine the job result
		// NOTE: when SLURM terminates a job, a line like this gets written to the output:
		//slurmstepd-mathpad: error: *** JOB 13 ON mathpad CANCELLED AT 2020-07-16T11:37:37 DUE TO TIME LIMIT ***
		//slurmstepd-mathpad: error: *** JOB 17 ON mathpad CANCELLED AT 2020-07-16T16:56:04 ***
		//slurmstepd: error: *** JOB 937 ON localhost CANCELLED AT 2022-11-17T14:56:35 ***
		val cancelPattern = Regex("slurmstepd(-\\w+)?: error: \\*\\*\\* JOB \\d+ ON \\w+ CANCELLED AT [0-9-:T]+ (.*) ?\\*\\*\\*")
		val cancelMatch = out
			?.lines()
			?.firstNotNullOfOrNull { cancelPattern.matchEntire(it) }
		// there wouldn't be more than one cancel line in the output, right?

		return if (cancelMatch != null) {
			val reason = cancelMatch
				.groupValues
				.getOrNull(2)
			ClusterJob.Result(ClusterJobResultType.Canceled, out, reason)
		} else {
			ClusterJob.Result(ClusterJobResultType.Success, out)
		}
	}

	override suspend fun deleteFiles(files: List<Path>) {

		// short circuit, just in case
		if (files.isEmpty()) {
			return
		}

		sshPool.connection {
			sftp {
				for (path in files) {
					try {
						delete(path)
					} catch (ex: NoRemoteFileException) {
						// delete failed, oh well I guess
					}
				}
			}
		}
	}
}
