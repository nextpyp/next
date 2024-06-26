package edu.duke.bartesaghi.micromon.cluster

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.deleteAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.readStringAs
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

	override fun validate(job: ClusterJob) {
		// whatever, it's fine
	}

	override fun validateDependency(depId: String) {
		// whatever, it's fine
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

		return ClusterJob.LaunchResult(null, "", null)
	}

	override suspend fun waitingReason(launchResult: ClusterJob.LaunchResult) =
		"no one cares"

	override suspend fun cancel(clusterJobs: List<ClusterJob>) {
		// nope
	}

	override suspend fun jobResult(clusterJob: ClusterJob, arrayIndex: Int?): ClusterJob.Result {

		// read the job output and cleanup the log file
		val outPath = clusterJob.outPath(arrayIndex)
		val out = outPath.readStringAs(clusterJob.osUsername)
		outPath.deleteAs(clusterJob.osUsername)

		return ClusterJob.Result(ClusterJobResultType.Success, out)
	}
}
