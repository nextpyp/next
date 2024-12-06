package edu.duke.bartesaghi.micromon.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.model.Updates.set
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.*
import edu.duke.bartesaghi.micromon.files.*
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.Micrograph
import edu.duke.bartesaghi.micromon.pyp.PypService
import edu.duke.bartesaghi.micromon.pyp.TiltSeries
import edu.duke.bartesaghi.micromon.sessions.Session
import edu.duke.bartesaghi.micromon.sessions.SingleParticleSession
import edu.duke.bartesaghi.micromon.sessions.TomographySession
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Writer
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque


/**
 * Since we're in container-land now, we can't just run main methods from the IDE anymore.
 * So make a special endpoint for doing debugging things
 *
 * WARNING: This should never be enabled on a production server!
 */
object DebugService {

	val profiles = ConcurrentLinkedDeque<Profiler>()


	fun init(routing: Routing) {

		if (!Config.instance.web.debug) {
			throw Error("This is not the way")
		}

		routing.route("debug") {

			get {
				call.respondText("Debug is active", ContentType.Text.Plain)
			}

			// generate synthetic testing data
			route("synthetic/genData") {

				get("project/{jobId}/{size}") {

					val jobId = call.parameters.getOrFail("jobId")
					val size = call.parameters.getOrFail("size").toIntOrNull()
						?: throw IllegalArgumentException("bad size")

					val job = Job.fromId(jobId)
						?: throw IllegalArgumentException("invalid job id")

					val response: String = when (job) {
						is SingleParticlePreprocessingJob -> syntheticGenerateMicrographs(job.idOrThrow, size)
						is TomographyPreprocessingJob -> syntheticGenerateTiltSeries(job.idOrThrow, size, 30)
						else -> throw IllegalArgumentException("unsupported job type: ${job::class.simpleName}")
					}

					call.respondText(response, ContentType.Text.Plain)
				}

				get("session/{sessionId}/{size}") {

					val sessionId = call.parameters.getOrFail("sessionId")
					val size = call.parameters.getOrFail("size").toIntOrNull()
						?: throw IllegalArgumentException("bad size")

					val session = Session.fromId(sessionId)
						?: throw IllegalArgumentException("invalid session id")

					val response: String = when (session) {
						is SingleParticleSession -> syntheticGenerateMicrographs(session.idOrThrow, size)
						is TomographySession -> syntheticGenerateTiltSeries(session.idOrThrow, size, 30)
					}

					call.respondText(response, ContentType.Text.Plain)
				}
			}

			get("synthetic/genCommands/grid/{sbatchId}/{h}/{w}") {

				val sbatchId = call.parameters.getOrFail("sbatchId")
				val w = call.parameters.getOrFail("w").toInt()
				val h = call.parameters.getOrFail("h").toInt()

				ClusterJob.get(sbatchId)
					?: throw IllegalArgumentException("sbatch job not found")

				val commands = (0 until h).map { y ->
					(0 until w).map { x ->
						"command $y,$x"
					}
				}

				Database.instance.cluster.launches.update(sbatchId,
					set("commands", CommandsGrid(commands).toDoc())
				)

				call.respondText("Done", ContentType.Text.Plain)
			}

			get("profile/micrographMetadata/{jobId}") {

				// TODO: consider optimizing this some more by using mogodb projections?
				//   https://mongodb.github.io/mongo-java-driver/3.4/driver/tutorials/perform-read-operations/
				//   apparently a lot of the overhead is the java driver's document parsing
				//   less stuff to parse could speed things up a lot
				//   also, we could precalculate and cache things like the avg motion, or particle counts
				//   that would avoid loading a lot of the micrograph metadata entirely

				val jobId = call.parameters.getOrFail("jobId")

				val p = Profiler()
				p.time("docs count") {
					Database.instance.micrographs.getAll(jobId) {
						it.count()
					}
				}
				val data = p.time("getAll") {
					Database.instance.micrographs.getAll(jobId) { cursor ->
						cursor
							.map {
								p.time("micrograph") {
									Micrograph(it)
								}
							}
							.map {
								p.time("data") {
									it.getMetadata()
								}
							}
							.toList()
					}
				}
				val json: String = p.time("json") {
					Json.encodeToString(data)
				}

				call.respondText(p.toString() + "\n\n\n" + json, ContentType.Text.Plain)
			}

			get("profile/slurm/array/{size}") {

				val size = call.parameters.getOrFail("size").toInt()

				call.respondTextStream {

					val startNs = System.nanoTime()

					fun elapsedS(start: Long = startNs, stop: Long = System.nanoTime()): Double =
						(stop - start)/1_000_000_000.0

					suspend fun writeln(msg: String = "") = slowIOs {
						if (msg.isNotEmpty()) {
							write("[%05.2f s] %s\n".format(elapsedS(), msg))
						} else {
							write("\n")
						}
						flush()
					}

					suspend fun flushProfiles() {
						while (true) {
							val p = profiles.pollFirst() ?: break
							writeln("\t\t\t\t\t$p")
						}
					}

					writeln("starting array job with $size elements ...")

					val testOwnerId = "ArrayJobStressTest"
					var listenerId: Long? = null
					val channel = Channel<Void>()
					var firstEndNs: Long? = null
					var lastEndNs: Long? = null
					var startedCount = 0
					var endedCount = 0
					var maxNumSimultaneous = 0

					// listen to cluster events
					val listener = object : ClusterJob.Listener {

						override suspend fun onSubmit(clusterJob: ClusterJob) {
							if (clusterJob.ownerId == testOwnerId) {
								writeln("job submitted (to micromon)")
							}
						}

						override suspend fun onStart(ownerId: String?, dbid: String) {
							if (ownerId == testOwnerId) {
								writeln("job started")
							}
						}

						override suspend fun onStartArray(ownerId: String?, dbid: String, arrayId: Int, numStarted: Int) {
							if (ownerId == testOwnerId) {
								startedCount = numStarted
								val numSimultaneous = startedCount - endedCount
								if (numSimultaneous > maxNumSimultaneous) {
									maxNumSimultaneous = numSimultaneous
								}
								writeln("\tstart %3d\t\t\t(simultaneous %3d)".format(arrayId, numSimultaneous))
								flushProfiles()
							}
						}

						override suspend fun onEndArray(ownerId: String?, dbid: String, arrayId: Int, numEnded: Int, numCanceled: Int, numFailed: Int) {
							if (ownerId == testOwnerId) {
								endedCount = numEnded
								if (firstEndNs == null) {
									firstEndNs = System.nanoTime()
								}
								if (numEnded == size) {
									lastEndNs = System.nanoTime()
								}
								writeln("\t\t\tstop  %3d".format(arrayId))
								flushProfiles()
							}
						}

						override suspend fun onEnd(ownerId: String?, dbid: String, resultType: ClusterJobResultType) {
							if (ownerId == testOwnerId) {
								writeln("job ended: result=$resultType")
								listenerId?.let { ClusterJob.removeListener(it) }
								channel.close()
								profiles.clear()
							}
						}
					}
					listenerId = ClusterJob.addListener(listener)

					// make a new pyp job
					ClusterJob(
						osUsername = null,
						ownerId = testOwnerId,
						webName = "PYP array job stress test",
						//containerId = Container.Pyp.id,
						containerId = null,
						commands = CommandsScript(
							commands = listOf(
								//Container.Pyp.run("pyp") + " --help"
								"uptime"
							),
							params = null,
							arraySize = size
						),
						dir = Paths.get("/tmp"),
						args = listOf("--mem=16m", "--cpus-per-task=1")
					).submit()

					// wait for the job to finish
					channel.receiveCatching()

					// write the final report
					writeln()
					firstEndNs?.let { start ->
						lastEndNs?.let { stop ->
							val s = elapsedS(start, stop)
							writeln("last array element ended %05.2f s after the first one".format(s))
							val rate = size/s
							writeln("element end processing rate: %5.2f elements/s".format(rate))
						}
					}
					writeln("maximum number of simultaneous array elements: $maxNumSimultaneous")
					writeln()
					writeln("test finished!")

					// falling out of this scope will close the writer and finish the HTTP response stream
				}
			}

			get("profile/micrographWrite/{jobId}/{size}") {

				val jobId = call.parameters.getOrFail("jobId")
				val size = call.parameters.getOrFail("size").toIntOrNull()
					?: throw IllegalArgumentException("bad size")

				val job = Job.fromId(jobId)
					?: throw IllegalArgumentException("invalid job id")

				val response: String = when (job) {
					is SingleParticlePreprocessingJob -> profileMicrographWrite(job, size)
					else -> throw IllegalArgumentException("unsupported job type: ${job::class.simpleName}")
				}

				call.respondText(response, ContentType.Text.Plain)
			}

			get("load/go") {

				suspend fun request(body: String): Pair<String,Long>? =
					try {
						val request = HttpClient()
							.request<HttpStatement>("http://localhost:8080/debug/load/task") {
								this.method = HttpMethod.Post
								this.body = body
							}
						val start = System.nanoTime()
						val response = request
							.execute()
							.readText()
						val elapsed = System.nanoTime() - start
						response to elapsed
					} catch (t: Throwable) {
						null
					}

				suspend fun Writer.trackedRequest(i: Int): kotlinx.coroutines.Job =
					launch(Dispatchers.IO) { // NOTE: launch outside of the netty/ktor thread pool
						writeln("Request $i: started")
						flush()
						val result = request("Hi, I'm $i")
						if (result != null) {
							val (response, elapsed) = result
							writeln("Request $i: got \"$response\" in ${elapsed/1_000_000} ms")
						} else {
							writeln("Request $i: failed")
						}
						flush()
					}

				call.respondTextStream {

					writeln("Starting requests ...")
					flush()

					val numTasks = 200
					val jobs = (0 until numTasks)
						.map { trackedRequest(it) }
					jobs.forEach { it.join() }

					writeln("done")
				}
			}

			post("load/task") f@{

				val body = call.receiveText()

				println("load/task: $body")

				// sink the thread for a bit
				//Thread.sleep(1_000)
				//delay(1_000)

				// do a big database read
				val jobId = "e9fHFasL1xwH5yki"
				TiltSeries.getAll(jobId) { tiltSerieses ->
					tiltSerieses
						.onEach { tiltSeries ->
							tiltSeries.getAvgRot()
							tiltSeries.getDriftMetadata()
						}
						.count()
				}
				//

				// write a tilt series ... sort of
				val numTilts = 41
				val numMotionSamples = 20
				val numDriftSamples = 20
				val numCtfSamples = 40
				// NOTE: this requires a hack in PypService.writeTiltSeries() to recognize
				//       a jobId instead of the usual webid(clusterJobId)
				val json = """
					|{
					|    "jobId": "e9fHFasL1xwH5yki",
					|    "tiltseries_id": "TS_42",
					|    "ctf": [
					|        2.112544531250000000e+04,
					|        1.415299996733665466e-02,
					|        2.177435156250000000e+04,
					|        2.047653906250000000e+04,
					|        3.621454954147338867e+00,
					|        1.415299996733665466e-02,
					|        1.240000000000000000e+03,
					|        1.200000000000000000e+03,
					|        5.000000000000000000e+01,
					|        1.100000000000000089e+00,
					|        3.000000000000000000e+02,
					|        1.000000000000000000e+04,
					|        7.172000122070312500e+02,
					|        0.000000000000000000e+00
					|    ],
					|    "xf": [
					|        ${(0 until numMotionSamples).joinToString(", ") { i ->
								"[1.0, 1.0, 1.0, 1.0, $i.1, $i.2]"
							}}
					|    ],
					|    "avgrot": [
					|        ${(0 until numTilts).joinToString(", ") { i ->
								"[$i.1, $i.2, $i.3, $i.4, $i.5, $i.6]"
							}}
					|    ],
					|    "metadata": {
					|        "tilts": [
					|            ${(0 until numTilts).joinToString(", ") { i -> "$i.0" }}
					|        ],
					|        "drift": [
					|            ${(0 until numTilts).joinToString(", ") { i ->
									"[${(0 until numDriftSamples).joinToString(", ") { j -> "[$i.1, $j.2]" }}]"
								}}
					|        ],
					|        "ctf_values": [
					|            ${(0 until numTilts).joinToString(", ") { i ->
									"[$i, $i.2, $i.3, $i.4, $i.5, $i.6]"
								}}
					|        ],
					|        "ctf_profiles": [
					|            ${(0 until numTilts).joinToString(", ") { i ->
									"[${listOf(
										"[${(0 until numCtfSamples).joinToString(", ") { j -> "$i.1$j" }}]",
										"[${(0 until numCtfSamples).joinToString(", ") { j -> "$i.2$j" }}]",
										"[${(0 until numCtfSamples).joinToString(", ") { j -> "$i.3$j" }}]",
										"[${(0 until numCtfSamples).joinToString(", ") { j -> "$i.4$j" }}]",
										"[${(0 until numCtfSamples).joinToString(", ") { j -> "$i.5$j" }}]",
										"[${(0 until numCtfSamples).joinToString(", ") { j -> "$i.6$j" }}]"
									).joinToString(", ")}]"
								}}
					|        ],
					|        "tilt_axis_angle": 5.0
					|    }
					|}
				""".trimMargin()
				val response = PypService.writeTiltSeries(ObjectMapper().readTree(json).asObject())
				if (response is JsonRpcFailure) {
					call.respondText("Failure: ${response.message}")
					return@f
				}
				//

				// try to clean up a job
				val clusterJobId = "drHqPbnoIZjHM6qM"
				try {
					Cluster.ended(
						clusterJobId,
						5,
						0
					)
				} catch (t: Throwable) {
					call.respondText("Failed: ${t.message}")
					return@f
				}
				//

				call.respondText("Success")
			}
		}
	}


	private suspend fun ApplicationCall.respondTextStream(block: suspend Writer.() -> Unit) {

		// disable compression for this response, otherwise streaming won't work
		// see: https://youtrack.jetbrains.com/issue/KTOR-5977/Kotlin-JS-Unable-to-stream-compressed-responses
		attributes.put(Compression.SuppressionAttribute, true)

		respondTextWriter(ContentType.Text.Plain) { block() }
	}

	private fun Writer.writeln(line: String) {
		write(line)
		write("\n")
	}


	/**
	 * Mimic the micrograph metadata writer function that PYP calls
	 */
	private fun syntheticGenerateMicrographs(ownerId: String, size: Int): String {

		// TODO: update this? if we ever need it again
		if (true) {
			throw Error("database format here is out of date!")
		}

		for (i in 0 until size) {

			val micrographId = "synthetic_$i"

			Database.instance.micrographs.write(ownerId, micrographId) {
				set("jobId", ownerId)
				set("micrographId", micrographId)
				set("timestamp", Instant.now().toEpochMilli())
				set("ctf", syntheticCtf().toDoc())
				set("avgrot", syntheticAvgrot().toDoc())
				set("xf", syntheticXF().toDoc())
				set("boxx", syntheticBoxx().toDoc())
			}
		}

		return "Generated $size synthetic micrographs for $ownerId"
	}

	private fun syntheticGenerateTiltSeries(ownerId: String, size: Int, tilts: Int): String {

		// TODO: update this? if we ever need it again
		if (true) {
			throw Error("database format here is out of date!")
		}

		for (i in 0 until size) {

			val tiltSeriesId = "synthetic_$i"

			Database.instance.tiltSeries.write(ownerId, tiltSeriesId) {
				set("jobId", ownerId)
				set("tiltSeriesId", tiltSeriesId)
				set("timestamp", Instant.now().toEpochMilli())
				set("ctf", syntheticCtf().toDoc())
				set("avgrot", syntheticAvgrot().toDoc())
				set("xf", syntheticXF().toDoc())
				set("boxx", syntheticBoxx().toDoc())
				set("metadata", syntheticDmd(tilts).toDoc())
			}
		}

		return "Generated $size synthetic tilt series with $tilts tilts for $ownerId"
	}

	private fun syntheticCtf(): CTF =
		CTF.from("""
			|2.112544531250000000e+04
			|1.415299996733665466e-02
			|2.177435156250000000e+04
			|2.047653906250000000e+04
			|3.621454954147338867e+00
			|1.415299996733665466e-02
			|1.240000000000000000e+03
			|1.200000000000000000e+03
			|5.000000000000000000e+01
			|1.100000000000000089e+00
			|3.000000000000000000e+02
			|1.000000000000000000e+04
			|7.172000122070312500e+02
			|0.000000000000000000e+00
		""".trimMargin())

	private fun syntheticAvgrot(): AVGROT =
		AVGROT.from("""
			|0.000000 0.001394 0.002789 0.004183 0.005577 0.006972 0.008366 0.009760 0.011154 0.012549 0.013943 0.015337 0.016732 0.018126 0.019520 0.020915 0.022309 0.023703 0.025098 0.026492 0.027886 0.029281 0.030675 0.032069 0.033463 0.034858 0.036252 0.037646 0.039041 0.040435 0.041829 0.043224 0.044618 0.046012 0.047407 0.048801 0.050195 0.051590 0.052984 0.054378 0.055772 0.057167 0.058561 0.059955 0.061350 0.062744 0.064138 0.065533 0.066927 0.068321 0.069716 0.071110 0.072504 0.073898 0.075293 0.076687 0.078081 0.079476 0.080870 0.082264 0.083659 0.085053 0.086447 0.087842 0.089236 0.090630 0.092025 0.093419 0.094813 0.096207 0.097602 0.098996 0.100390 0.101785 0.103179 0.104573 0.105968 0.107362 0.108756 0.110151 0.111545 0.112939 0.114334 0.115728 0.117122 0.118516 0.119911 0.121305 0.122699 0.124094 0.125488 0.126882 0.128277 0.129671 0.131065 0.132460 0.133854 0.135248 0.136642 0.138037 0.139431 0.140825 0.142220 0.143614 0.145008 0.146403 0.147797 0.149191 0.150586 0.151980 0.153374 0.154769 0.156163 0.157557 0.158951 0.160346 0.161740 0.163134 0.164529 0.165923 0.167317 0.168712 0.170106 0.171500 0.172895 0.174289 0.175683 0.177078 0.178472 0.179866 0.181260 0.182655 0.184049 0.185443 0.186838 0.188232 0.189626 0.191021 0.192415 0.193809 0.195204 0.196598 0.197992 0.199386 0.200781 0.202175 0.203569 0.204964 0.206358 0.207752 0.209147 0.210541 0.211935 0.213330 0.214724 0.216118 0.217513 0.218907 0.220301 0.221695 0.223090 0.224484 0.225878 0.227273 0.228667 0.230061 0.231456 0.232850 0.234244 0.235639 0.237033 0.238427 0.239822 0.241216 0.242610 0.244004 0.245399 0.246793 0.248187 0.249582 0.250976 0.252370 0.253765 0.255159 0.256553 0.257948 0.259342 0.260736 0.262131 0.263525 0.264919 0.266313 0.267708 0.269102 0.270496 0.271891 0.273285 0.274679 0.276074 0.277468 0.278862 0.280257 0.281651 0.283045 0.284439 0.285834 0.287228 0.288622 0.290017 0.291411 0.292805 0.294200 0.295594 0.296988 0.298383 0.299777 0.301171 0.302566 0.303960 0.305354 0.306748 0.308143 0.309537 0.310931 0.312326 0.313720 0.315114 0.316509 0.317903 0.319297 0.320692 0.322086 0.323480 0.324875 0.326269 0.327663 0.329057 0.330452 0.331846 0.333240 0.334635 0.336029 0.337423 0.338818 0.340212 0.341606 0.343001 0.344395 0.345789 0.347183 0.348578 0.349972 0.351366 0.352761 0.354155 0.355549 0.356944 0.358338 0.359732 0.361127 0.362521 0.363915 0.365310 0.366704 0.368098 0.369492 0.370887 0.372281 0.373675 0.375070 0.376464 0.377858 0.379253 0.380647 0.382041 0.383436 0.384830 0.386224 0.387619 0.389013 0.390407 0.391801 0.393196 0.394590 0.395984 0.397379 0.398773 0.400167 0.401562 0.402956 0.404350 0.405745 0.407139 0.408533 0.409927 0.411322 0.412716 0.414110 0.415505 0.416899 0.418293 0.419688 0.421082 0.422476 0.423871 0.425265 0.426659 0.428054 0.429448 0.430842 0.432236 0.433631 0.435025 0.436419 0.437814 0.439208 0.440602 0.441997 0.443391 0.444785 0.446180 0.447574 0.448968 0.450363 0.451757 0.453151 0.454545 0.455940 0.457334 0.458728 0.460123 0.461517 0.462911 0.464306 0.465700 0.467094 0.468489 0.469883 0.471277 0.472671 0.474066 0.475460 0.476854 0.478249 0.479643 0.481037 0.482432 0.483826 0.485220 0.486615 0.488009 0.489403 0.490798 0.492192 0.493586 0.494980 0.496375 0.497769 0.499163 0.500558 0.501952 0.503346 0.504741
			|      -0.03348       -0.03348       -0.03348       -0.03348       -0.03348       -0.03348       -0.03348       -0.03348       -0.03348       -0.03348       -0.03348       -0.03348       -0.03348       -0.03348        0.18300        1.11785        0.63676       -0.42499       -1.32542       -2.14430       -1.50047       -0.73023       -0.29304       -0.00648        0.03933        0.01983       -0.18962       -0.44246       -0.59189       -0.24603       -0.53665       -0.51239       -0.36092       -0.47316       -0.35554       -0.30545       -0.24480       -0.12966       -0.10094        0.07432        0.03158       -0.01296       -0.02582       -0.03713        0.03587       -0.01975        0.01538        0.08682       -0.09192       -0.09635       -0.14362       -0.17732       -0.11280        0.01482       -0.02091       -0.01228       -0.00276       -0.06670       -0.03315       -0.14689       -0.03667       -0.03129       -0.01473       -0.18647        0.01004        0.03592        0.12329        0.02494        0.03638       -0.03986       -0.06988        0.00166       -0.13898       -0.08962       -0.08300       -0.01120       -0.05511       -0.02405       -0.02178       -0.08587        0.01380        0.10672        0.02471       -0.14143       -0.07165       -0.02534       -0.05801       -0.07631       -0.05201        0.00566       -0.07823       -0.02946       -0.11420        0.01839       -0.00555       -0.14967        0.00905        0.06556       -0.01756       -0.07726       -0.05500       -0.01659        0.01639        0.06010       -0.05164       -0.07118       -0.08385       -0.05935       -0.05349       -0.04714       -0.02284       -0.04397       -0.03834        0.03314        0.01405       -0.02106       -0.04599       -0.08679       -0.04408       -0.03341       -0.02560       -0.06777       -0.03859        0.00205       -0.01312       -0.14502       -0.00852       -0.00769       -0.02347       -0.08137       -0.05421       -0.09443       -0.04489        0.02132        0.00791       -0.03055        0.00845       -0.03175        0.00233       -0.04154       -0.02499       -0.03023       -0.05783       -0.03136       -0.02884       -0.03135       -0.11004       -0.02929        0.01079       -0.00513       -0.05897       -0.05308       -0.04672       -0.05986       -0.00234        0.01253       -0.06442       -0.04732       -0.07706       -0.04662       -0.03371       -0.02394       -0.01005       -0.01720       -0.05820       -0.06276       -0.06909       -0.04414        0.00926       -0.02715       -0.01975        0.02249       -0.02211       -0.00715        0.01442       -0.06228       -0.01274       -0.09347       -0.06596       -0.05253       -0.08539       -0.10372        0.01936        0.03016       -0.04629       -0.05657       -0.02505        0.00036        0.00490       -0.02600       -0.07057       -0.07579       -0.04835       -0.03685        0.01065       -0.08283        0.01194       -0.04452       -0.01519       -0.06265       -0.03264       -0.02459       -0.03681       -0.05994       -0.02362       -0.04075       -0.09458       -0.06616       -0.02638       -0.02503       -0.03530        0.01938       -0.00620       -0.02646       -0.04301       -0.05389       -0.01592       -0.04660       -0.02944       -0.01325       -0.05372       -0.03222       -0.04188       -0.06603       -0.06239       -0.00843       -0.01503       -0.03254       -0.03812        0.01546       -0.03079       -0.03198       -0.06363       -0.06699       -0.04490       -0.01571       -0.04043       -0.04893       -0.04244       -0.02260       -0.00683       -0.00649       -0.05415       -0.03993       -0.00977       -0.07480       -0.04245       -0.05438       -0.00756       -0.03871       -0.02219       -0.03640       -0.05069       -0.02634       -0.01917       -0.01188       -0.05671       -0.05060       -0.07534       -0.03048       -0.07067        0.00246       -0.05951       -0.00273        0.01332       -0.02514       -0.03443       -0.04195       -0.06222       -0.04048       -0.02922       -0.01725       -0.04988       -0.05703       -0.02309       -0.07556        0.01954       -0.01608       -0.01623       -0.01759       -0.01139       -0.10819       -0.01942       -0.07202       -0.08466       -0.00893       -0.01500       -0.06454       -0.00040       -0.01174       -0.02552       -0.04825       -0.09502       -0.02643       -0.07749       -0.03366        0.01448        0.06638       -0.10416       -0.08037       -0.05713        0.01066        0.03229       -0.03780       -0.06833       -0.00172       -0.05768       -0.06996        0.00280        0.08138       -0.03067       -0.04317       -0.02822       -0.08463        0.02112       -0.04118       -0.12181       -0.03012        0.02885       -0.02880       -0.05533       -0.02384       -0.00745       -0.05557       -0.08676        0.02285       -0.14955       -0.04070       -0.06547       -0.03661        0.00075       -0.00202       -0.02919       -0.04013       -0.04497       -0.08017       -0.04055        0.08752        0.04105        0.03980       -0.10092       -0.00732       -0.03092       -0.12665       -0.10914       -0.10500        0.01667        0.02596       -0.10349        0.06439        0.06866       -0.12840       -0.10813        0.07064       -0.04773       -0.14949       -0.12455        0.05495        0.13338       -0.26457       -0.16089        0.13326        0.48057
			|5.823708 5.661373 5.501003 5.342855 5.186929 5.033225 4.881744 4.732484 4.585447 4.440631 4.428780 4.543606 4.684953 4.852818 5.047202 4.128877 2.510141 0.797581 -0.960699 -1.207641 -1.459078 -1.662252 -0.056466 1.631542 2.540253 2.210190 1.854727 1.109028 0.341400 0.139250 0.013278 -0.063833 -0.131364 -0.185220 -0.181912 0.022000 0.225101 0.428192 0.946602 1.292106 1.276305 1.055499 0.996516 1.140263 0.848662 0.735847 0.902192 1.185686 0.515678 -0.100120 -0.240449 -0.017229 0.018845 0.032479 0.041745 0.037662 0.165483 0.005123 -0.084049 -0.106446 -0.498014 0.137389 -0.148685 -0.606837 16.888366 -0.290098 -0.396899 -0.398341 -0.104651 0.010109 -0.048224 0.209459 0.378282 0.195378 0.071598 0.213456 0.122143 -0.006062 0.162123 0.032442 0.006707 -0.099977 0.039554 -0.147658 0.370946 0.459437 0.099513 0.165369 -0.184332 0.385016 3.563505 1.548170 -1.821172 0.043777 0.033205 0.268335 0.107459 0.232695 0.290017 -0.091144 0.027398 0.146910 0.392200 0.183829 0.002284 0.033568 -0.089278 0.031847 -0.103769 0.028676 -0.069788 0.081843 0.022942 0.052025 0.130281 0.139009 0.115242 0.000188 0.094376 0.061975 0.043141 0.077166 -0.042320 0.135565 0.047834 0.141234 0.127829 -0.060272 0.147705 0.084425 -0.076723 -0.071095 0.202981 0.039554 0.153038 0.332803 0.137579 -0.107921 0.035379 -0.103981 0.198797 -0.036020 -0.106906 0.282735 -0.098608 -0.046505 0.019332 0.204025 -0.085838 0.977173 0.161475 6.887505 -1.090833 1.748321 -0.006229 -0.082532 0.144524 -0.100624 0.052348 -0.150823 -0.082819 -0.055606 -0.019993 0.158399 0.385281 -0.003685 -0.044352 0.033292 0.196077 -0.195107 0.121454 0.043320 -0.112202 -0.047557 0.070271 -0.107704 -0.006572 -0.063983 -0.393145 -0.412072 -0.322334 0.091926 -0.108646 -0.584062 0.013807 0.171720 0.312680 0.391814 -0.235643 0.191428 -0.153701 0.226884 -0.536598 -0.477719 -0.049752 -0.816169 -0.349866 0.337446 0.076971 -0.094306 -0.069213 -0.077572 -0.132218 0.121944 -0.097336 0.116856 0.149279 0.126099 0.658706 0.197296 -0.008360 -0.375116 -0.097565 -0.133824 -0.483650 -0.227593 0.163120 -0.152121 0.217489 -0.044789 0.124510 0.257507 0.157054 -0.132349 -0.280279 -0.392968 0.315979 -0.036061 0.156689 0.426525 -0.370085 0.267501 0.293616 -0.176713 -0.224416 0.091816 -0.055667 -0.099464 0.113468 0.013774 0.050012 -0.253885 0.117490 -0.056446 -0.138481 0.071779 -0.288019 -0.289074 0.017613 -0.379122 0.171553 -0.386944 0.347358 0.124597 0.458811 -0.173808 -0.333734 -0.170857 -0.240193 0.089964 0.452191 0.018146 0.228157 -0.325170 -0.282699 0.068238 0.041641 -0.043605 0.206059 0.243556 -0.095529 -0.082446 0.521441 -0.010452 0.826584 -0.023583 0.547549 0.152391 0.036501 -0.055795 -0.043824 0.060812 -0.329462 -0.372168 0.307647 0.780982 -0.858335 -0.273857 -0.714689 -0.374126 -0.618867 -0.023864 0.278647 -0.004832 -0.027899 0.745623 -0.147818 0.024701 0.113291 0.253556 -0.794682 -0.425493 -0.239909 0.313980 0.288886 0.251667 0.119822 -0.230899 -0.336284 0.342765 -0.201533 -0.237054 0.394048 -0.099052 -0.076200 -0.654611 -0.118275 0.608064 0.241070 -0.373341 -0.059551 -0.238993 -0.114444 -0.060058 -0.035790 0.472219 -1.360152 -0.471509 0.229434 0.033861 0.789527 0.503082 0.111359 -0.800324 -0.407359 0.438956 -0.912921 0.135627 0.616371 0.384432 0.533929 -1.285858 0.346704 0.639838 0.203631 -0.418846 0.636410 0.292752 -1.090714 -0.675371 0.627832 0.336851 0.350676 1.782777 -0.378169 2.030300 0.337871 1.935920 3.303655 -2.066780 2.381944 1.257165 0.520131
			|0.070000 0.072534 0.080132 0.092785 0.110473 0.133163 0.160799 0.193296 0.230528 0.272316 0.318415 0.368499 0.422144 0.478813 0.537841 0.598416 0.659571 0.720172 0.778911 0.834310 0.884728 0.928373 0.963341 0.987645 0.999277 0.996274 0.976801 0.939250 0.882351 0.805288 0.707828 0.590445 0.454433 0.302006 0.136366 0.038266 0.216672 0.392727 0.559553 0.709759 0.835765 0.930197 0.986358 0.998742 0.963569 0.879306 0.747116 0.571205 0.358991 0.121056 0.129163 0.375974 0.602325 0.790984 0.925918 0.993817 0.985608 0.897816 0.733599 0.503278 0.224221 0.080041 0.381504 0.650121 0.856828 0.976902 0.993276 0.899388 0.701112 0.417354 0.079015 0.273803 0.596228 0.844294 0.981223 0.983419 0.845224 0.581471 0.227096 0.166556 0.538585 0.827793 0.983075 0.973179 0.793972 0.471495 0.059647 0.367752 0.729666 0.953319 0.989590 0.825022 0.487432 0.043084 0.414966 0.785720 0.982746 0.955392 0.703419 0.280648 0.214516 0.660732 0.943263 0.984380 0.766540 0.341143 0.181082 0.657294 0.951252 0.973339 0.710005 0.232586 0.319240 0.776748 0.993751 0.894945 0.504740 0.055006 0.600420 0.945097 0.964874 0.645253 0.092750 0.497275 0.908069 0.981833 0.683099 0.118967 0.495438 0.917110 0.971991 0.629927 0.024531 0.594961 0.964564 0.919346 0.470819 0.189637 0.768572 0.999994 0.770075 0.177805 0.502285 0.944483 0.928747 0.454388 0.249749 0.831216 0.989632 0.635202 0.054847 0.719282 0.999882 0.737115 0.066619 0.644359 0.995101 0.779281 0.112263 0.623519 0.994269 0.772491 0.082810 0.660615 0.999203 0.715118 0.021801 0.748026 0.994778 0.593935 0.199974 0.864049 0.949534 0.389897 0.439772 0.967677 0.819536 0.090717 0.705792 0.995795 0.560880 0.288601 0.926714 0.871676 0.155980 0.681608 0.995925 0.535207 0.348099 0.958131 0.802682 0.002189 0.807968 0.951431 0.305318 0.598663 0.999617 0.549682 0.374325 0.976557 0.726416 0.165294 0.912641 0.843499 0.011165 0.833768 0.914456 0.147631 0.759318 0.953440 0.242678 0.702312 0.972275 0.297592 0.670475 0.978963 0.314080 0.667419 0.977069 0.292888 0.693353 0.965596 0.233441 0.745208 0.939077 0.134375 0.816155 0.888033 0.005095 0.894610 0.799984 0.182782 0.963340 0.661424 0.390552 0.999145 0.461468 0.610835 0.974273 0.196897 0.814179 0.860651 0.121129 0.959045 0.637757 0.460928 0.996553 0.303864 0.767182 0.881861 0.111517 0.965519 0.592618 0.539384 0.978644 0.150899 0.874752 0.755325 0.360207 0.999651 0.306539 0.798842 0.827829 0.266582 0.998658 0.361097 0.775697 0.837394 0.269436 0.999590 0.320328 0.814170 0.788842 0.367633 0.995491 0.181447 0.897842 0.663494 0.547809 0.949825 0.060382 0.981367 0.428822 0.771803 0.802310 0.390829 0.986041 0.063048 0.958371 0.490090 0.744528 0.811023 0.402116 0.978743 0.003115 0.980754 0.382336 0.834062 0.697995 0.575907 0.907701 0.253296 0.996567 0.086448 0.968350 0.402527 0.840530 0.664730 0.638709 0.854804 0.391368 0.965798 0.125777 0.999979 0.134882 0.966249 0.372951 0.877489 0.576632 0.748231 0.739721 0.592794 0.860551 0.424045 0.941096 0.252742 0.985651 0.087137 0.999965 0.066959 0.990372 0.205809 0.963183 0.327498 0.924275 0.431536 0.878856 0.518471 0.831291 0.589354 0.785211 0.645650 0.743417 0.688900 0.708085 0.720623 0.680713 0.742010 0.662405 0.754034 0.653703 0.757266 0.654933 0.751934 0.665939 0.737839 0.686270 0.714422 0.715161 0.680821 0.751377 0.635953 0.793273
			|1.000000 1.000000 1.000000 1.000000 1.000000 1.000000 1.000000 1.000000 1.000000 1.000000 1.000000 1.000000 1.000000 1.000000 1.000000 0.006242 0.006242 0.006242 0.006242 0.006242 0.006242 0.006242 0.006242 0.006242 0.006242 0.050038 0.050038 0.050038 0.050038 0.050038 0.050038 0.050038 0.050038 0.050038 0.050038 0.050038 0.050038 0.026771 0.045898 -0.000345 -0.012448 0.007018 0.040144 0.461430 0.410891 0.390101 0.349350 0.307450 0.356986 0.413381 0.427282 0.429815 0.418563 0.434409 0.491996 0.473865 0.447808 0.440318 0.444399 0.430148 0.336136 0.350807 0.331451 0.367769 0.284382 0.213765 0.174144 0.171609 0.202077 0.160548 0.165689 0.167328 0.149446 0.151224 0.101190 0.033581 0.075903 0.116849 0.145852 0.150589 0.181536 0.159843 0.117400 0.079905 0.119378 0.148189 0.205167 0.204375 0.177883 0.299129 0.269166 0.286352 0.351077 0.332954 0.363600 0.370741 0.345110 0.276468 0.230107 0.205513 0.215882 0.243385 0.270659 0.316306 0.380898 0.409657 0.387673 0.379461 0.462453 0.425427 0.415710 0.455005 0.436787 0.484780 0.442931 0.436210 0.373655 0.301042 0.307817 0.168143 0.108218 0.116447 0.214158 0.215495 0.297619 0.223059 0.284350 0.305391 0.309803 0.308106 0.266114 0.246239 0.268460 0.156854 0.153970 0.181731 0.196174 0.173553 0.101459 0.120829 0.064887 -0.009618 0.006054 -0.027386 -0.012916 0.032837 0.021887 0.128203 0.010797 -0.003198 0.093276 0.139839 0.130369 0.047595 0.056428 0.033339 0.031825 -0.020001 -0.030128 -0.111324 -0.115663 -0.068084 -0.085419 -0.134618 -0.139401 -0.141384 -0.173641 -0.155727 -0.224495 -0.195000 -0.192368 -0.146656 -0.119885 -0.136725 -0.178538 -0.216430 -0.110856 -0.254807 -0.272574 -0.254764 -0.264953 -0.318870 -0.326022 -0.307593 -0.383790 -0.401095 -0.195451 -0.210862 -0.216392 -0.197309 -0.252754 -0.174336 -0.118673 -0.127996 -0.120130 -0.122080 -0.135774 -0.066379 -0.043815 -0.069508 -0.109495 -0.036220 0.039825 0.037141 0.038462 0.088563 0.075176 0.091161 0.087061 0.079579 0.129587 0.177043 0.180685 0.169454 0.203973 0.215809 0.230032 0.257966 0.284451 0.316076 0.300432 0.285006 0.183327 0.206169 0.190093 0.144421 0.120906 0.159132 0.133690 0.101262 0.010064 0.008631 -0.020271 0.020049 0.048075 0.036198 -0.057000 -0.053401 0.022137 0.055368 0.074977 0.068612 0.017831 -0.008943 -0.129975 -0.080945 -0.067735 -0.042954 -0.043293 -0.014842 -0.020345 -0.002865 -0.071149 -0.110384 0.042468 0.045744 0.073876 -0.048786 -0.011849 0.092783 0.054425 0.023845 0.010066 -0.000804 -0.009105 0.138401 0.140849 -0.049022 0.002543 0.074211 0.065021 0.125711 0.153317 0.135251 0.085855 0.058604 0.065242 0.144422 0.153990 0.138962 0.158593 0.176989 0.104721 0.133496 0.159025 0.140667 0.036144 0.065214 0.072059 0.175190 0.131009 0.113463 0.130621 0.065345 0.021539 -0.012454 0.080583 0.075386 0.075012 0.042315 -0.002370 -0.071827 0.127779 0.099184 0.096154 -0.032492 -0.031580 0.020377 0.025818 0.017372 -0.016764 0.033772 0.033019 0.035482 0.036277 0.054385 0.049484 0.059094 0.063840 0.060928 0.065506 0.081170 0.040707 0.012010 -0.010445 0.000328 0.007695 0.014064 0.032278 0.032278 0.032278 0.025031 0.075718 0.049620 0.021683 0.010712 0.010712 0.010712 0.010712 0.010712 0.010712 0.026874 0.026874 0.026874 0.026874 0.026874 0.026874 0.026874 0.026874 0.006042 0.006042 0.006042 0.006042 0.006042 0.006042 -0.001969 -0.001969 -0.001969 -0.001969 -0.001969 -0.001969 -0.001969 -0.001969
			|0.000000 0.000000 0.000000 0.000000 0.000000 0.000000 0.000000 0.000000 0.000000 0.000000 0.000000 0.000000 0.000000 0.000000 0.000000 0.264906 0.264906 0.264906 0.264906 0.264906 0.264906 0.264906 0.264906 0.264906 0.264906 0.298142 0.298142 0.298142 0.298142 0.298142 0.298142 0.298142 0.298142 0.298142 0.298142 0.298142 0.298142 0.298142 0.298142 0.298142 0.298142 0.298142 0.298142 0.328798 0.328798 0.328798 0.328798 0.328798 0.328798 0.328798 0.328798 0.328798 0.328798 0.328798 0.328798 0.328798 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.371391 0.371391 0.371391 0.371391 0.371391 0.371391 0.371391 0.371391 0.384900 0.384900 0.384900 0.384900 0.384900 0.384900 0.384900 0.371391 0.371391 0.371391 0.371391 0.371391 0.371391 0.371391 0.384900 0.384900 0.384900 0.384900 0.384900 0.384900 0.371391 0.371391 0.371391 0.371391 0.371391 0.371391 0.371391 0.371391 0.371391 0.371391 0.371391 0.371391 0.384900 0.384900 0.384900 0.384900 0.384900 0.384900 0.384900 0.384900 0.384900 0.384900 0.371391 0.371391 0.371391 0.371391 0.371391 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.384900 0.384900 0.384900 0.384900 0.371391 0.371391 0.371391 0.371391 0.338062 0.338062 0.338062 0.338062 0.338062 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.359211 0.348155 0.348155 0.348155 0.348155 0.384900 0.384900 0.384900 0.338062 0.338062 0.338062 0.338062 0.338062 0.338062 0.338062 0.338062 0.371391 0.371391 0.371391 0.328798 0.328798 0.328798 0.328798 0.359211 0.359211 0.359211 0.320256 0.320256 0.320256 0.320256 0.348155 0.348155 0.348155 0.348155 0.348155 0.348155 0.304997 0.304997 0.304997 0.304997 0.338062 0.338062 0.338062 0.338062 0.338062 0.338062 0.328798 0.328798 0.328798 0.328798 0.328798 0.328798 0.328798 0.328798 0.328798 0.320256 0.320256 0.320256 0.320256 0.320256 0.320256 0.312348 0.312348 0.312348 0.312348 0.312348 0.312348 0.312348 0.312348 0.312348 0.304997 0.304997 0.304997 0.304997 0.304997 0.304997 0.348155 0.348155 0.298142 0.298142 0.298142 0.298142 0.298142 0.298142 0.338062 0.338062 0.291730 0.291730 0.291730 0.285714 0.285714 0.285714 0.328798 0.328798 0.285714 0.285714 0.285714 0.328798 0.328798 0.280056 0.280056 0.280056 0.274721 0.274721 0.274721 0.320256 0.320256 0.274721 0.274721 0.274721 0.312348 0.312348 0.312348 0.312348 0.264906 0.264906 0.264906 0.304997 0.304997 0.264906 0.264906 0.264906 0.304997 0.304997 0.298142 0.298142 0.256074 0.256074 0.256074 0.298142 0.298142 0.291730 0.291730 0.251976 0.251976 0.251976 0.291730 0.291730 0.285714 0.285714 0.285714 0.285714 0.248069 0.248069 0.248069 0.285714 0.285714 0.280056 0.280056 0.280056 0.280056 0.240772 0.240772 0.240772 0.274721 0.274721 0.274721 0.274721 0.274721 0.274721 0.269680 0.269680 0.269680 0.269680 0.269680 0.269680 0.269680 0.269680 0.230940 0.230940 0.230940 0.264906 0.264906 0.264906 0.264906 0.260378 0.260378 0.260378 0.260378 0.260378 0.260378 0.256074 0.256074 0.256074 0.256074 0.256074 0.256074 0.256074 0.256074 0.251976 0.251976 0.251976 0.251976 0.251976 0.251976 0.248069 0.248069 0.248069 0.248069 0.248069 0.248069 0.248069 0.248069
		""".trimMargin())

	private fun syntheticXF(): XF =
		XF.from("""
			|    1.0000000     0.0000000     0.0000000     1.0000000   -21.0625910     4.0798264
			|    1.0000000     0.0000000     0.0000000     1.0000000   -15.1825272     4.4124846
			|    1.0000000     0.0000000     0.0000000     1.0000000   -12.0926546     4.6373954
			|    1.0000000     0.0000000     0.0000000     1.0000000   -11.2097728     4.7241400
			|    1.0000000     0.0000000     0.0000000     1.0000000   -10.8991090     4.6112910
			|    1.0000000     0.0000000     0.0000000     1.0000000   -10.5711910     4.2577136
			|    1.0000000     0.0000000     0.0000000     1.0000000   -10.2198182     3.7592264
			|    1.0000000     0.0000000     0.0000000     1.0000000    -9.3832454     3.1964936
			|    1.0000000     0.0000000     0.0000000     1.0000000    -8.4038528     2.5839864
			|    1.0000000     0.0000000     0.0000000     1.0000000    -7.6704072     1.9419554
			|    1.0000000     0.0000000     0.0000000     1.0000000    -6.9489218     1.3241218
			|    1.0000000     0.0000000     0.0000000     1.0000000    -6.4633790     0.8103170
			|    1.0000000     0.0000000     0.0000000     1.0000000    -5.7527454     0.4579122
			|    1.0000000     0.0000000     0.0000000     1.0000000    -4.9843818     0.2786148
			|    1.0000000     0.0000000     0.0000000     1.0000000    -4.2357036     0.2549242
			|    1.0000000     0.0000000     0.0000000     1.0000000    -3.6874910     0.3145596
			|    1.0000000     0.0000000     0.0000000     1.0000000    -3.3196236     0.3958306
			|    1.0000000     0.0000000     0.0000000     1.0000000    -3.1615618     0.4633396
			|    1.0000000     0.0000000     0.0000000     1.0000000    -2.9147582     0.5123466
			|    1.0000000     0.0000000     0.0000000     1.0000000    -2.7093728     0.5489516
			|    1.0000000     0.0000000     0.0000000     1.0000000    -2.3061472     0.5716222
			|    1.0000000     0.0000000     0.0000000     1.0000000    -1.6790172     0.5778660
			|    1.0000000     0.0000000     0.0000000     1.0000000    -1.0632328     0.5422850
			|    1.0000000     0.0000000     0.0000000     1.0000000    -0.6533966     0.4320608
			|    1.0000000     0.0000000     0.0000000     1.0000000    -0.2674458     0.2442036
			|    1.0000000     0.0000000     0.0000000     1.0000000     0.0000000     0.0000000
			|    1.0000000     0.0000000     0.0000000     1.0000000     0.1877990    -0.2692790
			|    1.0000000     0.0000000     0.0000000     1.0000000     0.3449192    -0.4846584
			|    1.0000000     0.0000000     0.0000000     1.0000000     0.5879304    -0.6072626
			|    1.0000000     0.0000000     0.0000000     1.0000000     0.7000450    -0.6641580
			|    1.0000000     0.0000000     0.0000000     1.0000000     0.9195882    -0.6572924
			|    1.0000000     0.0000000     0.0000000     1.0000000     1.3590790    -0.5938308
			|    1.0000000     0.0000000     0.0000000     1.0000000     1.7194928    -0.5101858
			|    1.0000000     0.0000000     0.0000000     1.0000000     2.0644064    -0.4393440
			|    1.0000000     0.0000000     0.0000000     1.0000000     2.3787036    -0.4079026
			|    1.0000000     0.0000000     0.0000000     1.0000000     2.3603518    -0.4332852
			|    1.0000000     0.0000000     0.0000000     1.0000000     2.3510382    -0.4609396
			|    1.0000000     0.0000000     0.0000000     1.0000000     2.2937036    -0.4664486
			|    1.0000000     0.0000000     0.0000000     1.0000000     2.4215872    -0.4353142
			|    1.0000000     0.0000000     0.0000000     1.0000000     2.5076146    -0.3717286
			|    1.0000000     0.0000000     0.0000000     1.0000000     2.6670764    -0.3200354
			|    1.0000000     0.0000000     0.0000000     1.0000000     2.7257054    -0.3182040
			|    1.0000000     0.0000000     0.0000000     1.0000000     2.9074936    -0.3971218
			|    1.0000000     0.0000000     0.0000000     1.0000000     3.3459172    -0.5900814
			|    1.0000000     0.0000000     0.0000000     1.0000000     3.0525672    -0.9304500
			|    1.0000000     0.0000000     0.0000000     1.0000000     2.9765672    -1.3803682
			|    1.0000000     0.0000000     0.0000000     1.0000000     2.9830810    -1.8995390
			|    1.0000000     0.0000000     0.0000000     1.0000000     3.0696900    -2.4918764
			|    1.0000000     0.0000000     0.0000000     1.0000000     3.7743964    -3.1477200
			|    1.0000000     0.0000000     0.0000000     1.0000000     4.7271490    -3.8229672
		""".trimMargin())

	private fun syntheticBoxx(): BOXX =
		BOXX.from("""
			|426.00	258.00	0.00	0.00	1.00	1.00
			|798.00	264.00	0.00	0.00	1.00	0.00
			|528.00	378.00	0.00	0.00	1.00	1.00
			|348.00	396.00	0.00	0.00	1.00	0.00
			|1128.00	396.00	0.00	0.00	1.00	1.00
			|816.00	414.00	0.00	0.00	1.00	0.00
			|972.00	444.00	0.00	0.00	1.00	0.00
			|768.00	522.00	0.00	0.00	1.00	0.00
			|288.00	546.00	0.00	0.00	1.00	0.00
			|396.00	570.00	0.00	0.00	1.00	0.00
			|864.00	570.00	0.00	0.00	1.00	0.00
			|1146.00	702.00	0.00	0.00	1.00	0.00
			|870.00	762.00	0.00	0.00	1.00	0.00
			|1080.00	792.00	0.00	0.00	1.00	0.00
			|348.00	942.00	0.00	0.00	1.00	0.00
			|936.00	948.00	0.00	0.00	1.00	0.00
			|1104.00	960.00	0.00	0.00	1.00	0.00
			|402.00	1020.00	0.00	0.00	1.00	0.00
			|972.00	1080.00	0.00	0.00	1.00	0.00
		""".trimMargin())

	private fun syntheticDmd(tilts: Int): DMD =
		DMD(
			tilts = (0 until tilts).map {
				(it + 1)*10.0
			},
			drifts = (0 until tilts).map {
				syntheticXF().samples.map { sample ->
					DMD.DriftXY(sample.x, sample.y)
				}
			},
			ctfValues = (0 until tilts).map { i ->
				syntheticCtf().let { ctf ->
					DMD.CtfTiltData(
						index = i,
						defocus1 = ctf.defocus1,
						defocus2 = ctf.defocus2,
						astigmatism = ctf.angast,
						cc = ctf.cc,
						resolution = ctf.cccc // ??? not that it matters in testing data, but is this even right?
					)
				}
			},
			ctfProfiles = (0 until tilts).map {
				syntheticAvgrot()
			},
			tiltAxisAngle = 5.0 // chosen by fair die roll, guaranteed to be random
		)

	private fun profileMicrographWrite(job: Job, size: Int): String {

		val jsonstr = """
			|{
			|  "jsonrpc": "2.0",
			|  "method": "write_micrograph",
			|  "params": {
			|    "webid": "dL2AMDjB5nFHQQDC",
			|    "micrograph_id": "May08_06.30.43.bin",
			|    "ctf": [
			|      13542.095703125,
			|      0.18886999785900116,
			|      13604.9306640625,
			|      13479.259765625,
			|      55.91462326049805,
			|      0.18886999785900116,
			|      1240.0,
			|      1200.0,
			|      50.0,
			|      1.1,
			|      300.0,
			|      10000.0,
			|      4.243786811828613,
			|      0.0
			|    ],
			|    "avgrot": [
			|      [0.0, 0.01122, -0.178089, 0.07, 1.0, 0.0],
			|      [0.001394, 0.01122, -0.190101, 0.071624, 1.0, 0.0],
			|      [0.002789, 0.01122, -0.203919, 0.076496, 1.0, 0.0],
			|      [0.004183, 0.01122, -0.219981, 0.084611, 1.0, 0.0],
			|      [0.005577, 0.01122, -0.238882, 0.095962, 1.0, 0.0],
			|      [0.006972, 0.01122, -0.261447, 0.110538, 1.0, 0.0],
			|      [0.008366, 0.01122, -0.288854, 0.12832, 1.0, 0.0],
			|      [0.00976, 0.01122, -0.322848, 0.149281, 1.0, 0.0],
			|      [0.011154, 0.01122, -0.366125, 0.173383, 1.0, 0.0],
			|      [0.012549, 0.01122, -0.451133, 0.200572, 1.0, 0.0],
			|      [0.013943, 0.01122, -0.578841, 0.230776, 1.0, 0.0],
			|      [0.015337, 0.01122, -0.77231, 0.2639, 1.0, 0.0],
			|      [0.016732, 0.01122, -1.091066, 0.299823, 1.0, 0.0],
			|      [0.018126, 0.01122, -1.696917, 0.338389, 1.0, 0.0],
			|      [0.01952, 0.42159, -3.123417, 0.379408, 1.0, 0.0],
			|      [0.020915, 2.79368, -13.127119, 0.422646, 0.343314, 0.237356],
			|      [0.022309, 3.22147, 6.6683, 0.467819, 0.343314, 0.237356],
			|      [0.023703, 1.90312, 2.647257, 0.514593, 0.343314, 0.237356],
			|      [0.025098, -1.50314, 0.936255, 0.562575, 0.343314, 0.237356],
			|      [0.026492, -3.47525, -0.000783, 0.611309, 0.343314, 0.237356],
			|      [0.027886, -2.95694, -0.72168, 0.660272, 0.343314, 0.237356],
			|      [0.029281, -1.21354, 0.350465, 0.708873, 0.343314, 0.237356],
			|      [0.030675, 0.77146, 1.475519, 0.756449, 0.343314, 0.237356],
			|      [0.032069, 2.52814, 2.2462, 0.802268, 0.343314, 0.237356],
			|      [0.033463, 2.98304, 2.700943, 0.845527, 0.343314, 0.237356],
			|      [0.034858, 2.63052, 2.83607, 0.885359, 0.343314, 0.237356],
			|      [0.036252, 1.39793, 1.775837, 0.92084, 0.343314, 0.237356],
			|      [0.037646, -0.84367, 0.741136, 0.950995, 0.343314, 0.237356],
			|      [0.039041, -1.68388, 0.252789, 0.974815, 0.343314, 0.237356],
			|      [0.040435, -1.3203, -0.109135, 0.991274, 0.343314, 0.237356],
			|      [0.041829, -0.70484, 0.423365, 0.999343, 0.343314, 0.237356],
			|      [0.043224, 0.30794, 0.9197, 0.998021, 0.296518, 0.264906],
			|      [0.044618, 0.79787, 1.30258, 0.98636, 0.296518, 0.264906],
			|      [0.046012, 0.64818, 1.305947, 0.963496, 0.296518, 0.264906],
			|      [0.047407, 0.15187, 1.001165, 0.928687, 0.296518, 0.264906],
			|      [0.048801, -0.50084, 0.618845, 0.881347, 0.296518, 0.264906], 
			|      [0.050195, -0.6574, 0.341502, 0.821088, 0.296518, 0.264906],
			|      [0.05159, -0.68912, 0.293494, 0.747763, 0.296518, 0.264906],
			|      [0.052984, -0.68554, 0.249398, 0.661504, 0.296518, 0.264906],
			|      [0.054378, -0.73797, 0.224904, 0.562761, 0.296518, 0.264906],
			|      [0.055772, -0.68639, 0.230931, 0.452341, 0.296518, 0.264906],
			|      [0.057167, -0.87405, 0.081064, 0.331433, 0.296518, 0.264906],
			|      [0.058561, -1.03349, -0.002958, 0.201631, 0.296518, 0.264906],
			|      [0.059955, -0.9299, -0.006823, 0.064947, 0.29455, 0.264906],
			|      [0.06135, -0.81955, 0.072212, 0.076193, 0.306523, 0.264906],
			|      [0.062744, -0.75858, 0.130064, 0.218965, 0.316813, 0.264906],
			|      [0.064138, -0.49716, 0.278126, 0.360187, 0.324727, 0.264906],
			|      [0.065533, -0.37648, 0.434793, 0.496366, 0.324398, 0.264906],
			|      [0.066927, -0.09466, 0.58493, 0.623782, 0.347234, 0.264906],
			|      [0.068321, 0.09367, 0.751688, 0.738578, 0.410731, 0.264906],
			|      [0.069716, 0.35295, 0.932311, 0.836885, 0.441268, 0.264906],
			|      [0.07111, 0.42795, 1.01977, 0.914954, 0.442238, 0.264906],
			|      [0.072504, 0.50179, 1.071257, 0.969318, 0.443407, 0.264906],
			|      [0.073898, 0.60297, 1.117042, 0.996952, 0.454249, 0.264906],
			|      [0.075293, 0.65188, 1.1757, 0.995454, 0.975459, 0.298142],
			|      [0.076687, 0.50887, 1.063177, 0.963214, 0.975364, 0.298142],
			|      [0.078081, 0.4055, 0.984863, 0.899582, 0.974692, 0.298142],
			|      [0.079476, 0.24537, 0.838886, 0.805012, 0.974147, 0.298142],
			|      [0.08087, -0.01851, 0.60094, 0.68118, 0.974027, 0.298142],
			|      [0.082264, -0.2358, 0.444358, 0.531051, 0.975194, 0.298142],
			|      [0.083659, -0.43929, 0.251231, 0.358914, 0.976803, 0.298142],
			|      [0.085053, -0.70254, 0.05093, 0.170329, 0.981585, 0.298142],
			|      [0.086447, -0.64141, 0.02034, 0.027975, 0.986463, 0.298142],
			|      [0.087842, -0.54105, 0.094612, 0.228291, 0.987787, 0.298142],
			|      [0.089236, -0.4708, 0.152423, 0.422193, 0.988484, 0.298142],
			|      [0.09063, -0.23628, 0.309647, 0.600879, 0.99333, 0.298142],
			|      [0.092025, 0.05976, 0.515566, 0.755576, 0.993246, 0.298142],
			|      [0.093419, 0.27147, 0.707171, 0.877995, 0.99354, 0.298142],
			|      [0.094813, 0.57602, 0.921025, 0.960828, 0.994225, 0.298142],
			|      [0.096207, 0.65877, 1.032686, 0.998241, 0.995191, 0.298142],
			|      [0.097602, 0.59511, 0.960275, 0.986357, 0.998065, 0.320256],
			|      [0.098996, 0.47633, 0.895934, 0.923669, 0.998165, 0.320256],
			|      [0.10039, 0.2498, 0.711143, 0.811369, 0.998078, 0.320256],
			|      [0.101785, -0.07791, 0.438178, 0.65354, 0.998015, 0.320256],
			|      [0.103179, -0.29333, 0.246556, 0.457191, 0.998017, 0.320256],
			|      [0.104573, -0.46196, 0.084051, 0.232105, 0.998019, 0.320256],
			|      [0.105968, -0.56278, -0.013416, 0.009527, 0.997782, 0.320256],
			|      [0.107362, -0.50639, -0.009389, 0.253665, 0.997129, 0.320256],
			|      [0.108756, -0.29972, 0.103407, 0.485186, 0.99618, 0.320256],
			|      [0.110151, 0.04992, 0.396125, 0.688814, 0.995523, 0.320256],
			|      [0.111545, 0.27598, 0.590256, 0.850161, 0.99374, 0.320256],
			|      [0.112939, 0.48121, 0.730595, 0.956831, 0.992349, 0.320256],
			|      [0.114334, 0.47388, 0.777832, 0.999484, 0.991138, 0.320256],
			|      [0.115728, 0.45577, 0.731833, 0.972796, 0.990642, 0.328798],
			|      [0.117122, 0.3695, 0.71944, 0.876206, 0.990859, 0.328798],
			|      [0.118516, 0.02122, 0.403426, 0.714355, 0.991216, 0.328798],
			|      [0.119911, -0.20643, 0.192032, 0.497159, 0.988261, 0.328798],
			|      [0.121305, -0.29731, 0.146941, 0.239442, 0.979381, 0.328798],
			|      [0.122699, -0.42617, -0.031839, 0.039871, 0.972998, 0.328798],
			|      [0.124094, -0.34708, 0.018261, 0.319024, 0.965014, 0.328798],
			|      [0.125488, -0.12743, 0.159814, 0.575043, 0.965698, 0.328798],
			|      [0.126882, 0.09575, 0.348632, 0.785654, 0.953608, 0.328798],
			|      [0.128277, 0.25611, 0.442472, 0.931307, 0.949024, 0.328798],
			|      [0.129671, 0.40699, 0.627571, 0.997155, 0.949942, 0.328798],
			|      [0.131065, 0.32331, 0.57294, 0.974784, 0.944263, 0.338062],
			|      [0.13246, 0.20351, 0.468736, 0.863482, 0.945234, 0.338062],
			|      [0.133854, -0.13408, 0.191094, 0.670875, 0.946999, 0.338062],
			|      [0.135248, -0.22309, 0.105832, 0.412799, 0.944422, 0.338062],
			|      [0.136642, -0.28145, 0.004531, 0.112304, 0.932623, 0.338062],
			|      [0.138037, -0.24555, 0.026692, 0.202156, 0.93455, 0.338062],
			|      [0.139431, -0.09198, 0.13041, 0.499283, 0.936649, 0.338062],
			|      [0.140825, 0.0415, 0.232206, 0.748032, 0.923968, 0.338062],
			|      [0.14222, 0.20061, 0.345358, 0.920954, 0.918924, 0.338062],
			|      [0.143614, 0.26709, 0.430077, 0.997428, 0.922033, 0.338062],
			|      [0.145008, 0.14627, 0.353392, 0.966418, 0.926509, 0.338062],
			|      [0.146403, 0.05169, 0.252553, 0.828361, 0.92612, 0.338062],
			|      [0.147797, -0.09115, 0.171615, 0.595869, 0.921905, 0.338062],
			|      [0.149191, -0.15476, 0.022804, 0.293064, 0.925033, 0.338062],
			|      [0.150586, -0.16087, 0.050123, 0.046559, 0.926923, 0.338062],
			|      [0.15198, -0.1679, 0.013833, 0.383579, 0.927756, 0.338062],
			|      [0.153374, 0.06909, 0.173651, 0.677118, 0.925203, 0.338062],
			|      [0.154769, 0.18333, 0.322474, 0.889886, 0.924351, 0.338062],
			|      [0.156163, 0.22263, 0.356815, 0.99312, 0.924512, 0.338062],
			|      [0.157557, 0.1805, 0.311645, 0.970854, 0.923213, 0.348155],
			|      [0.158951, 0.0468, 0.202554, 0.822787, 0.91639, 0.348155],
			|      [0.160346, -0.14156, 0.039054, 0.565271, 0.910353, 0.348155],
			|      [0.16174, -0.17614, -0.005645, 0.230085, 0.906911, 0.348155],
			|      [0.163134, -0.1338, -0.030574, 0.139043, 0.919526, 0.348155],
			|      [0.164529, -0.08887, -0.006949, 0.491846, 0.919992, 0.348155],
			|      [0.165923, 0.11299, 0.151122, 0.778313, 0.91806, 0.348155],
			|      [0.167317, 0.19917, 0.252822, 0.955923, 0.917177, 0.348155],
			|      [0.168712, 0.15424, 0.276328, 0.996317, 0.917484, 0.338062],
			|      [0.170106, 0.06364, 0.175925, 0.890319, 0.908777, 0.338062],
			|      [0.1715, -0.04734, 0.122012, 0.650396, 0.915689, 0.338062],
			|      [0.172895, -0.17937, -0.050024, 0.30994, 0.916735, 0.338062],
			|      [0.174289, -0.10757, -0.025727, 0.080788, 0.913488, 0.338062],
			|      [0.175683, -0.01786, 0.058835, 0.461698, 0.916706, 0.338062],
			|      [0.177078, 0.03838, 0.126086, 0.771988, 0.919706, 0.338062],
			|      [0.178472, 0.1428, 0.182145, 0.96001, 0.910802, 0.338062],
			|      [0.179866, 0.09381, 0.224909, 0.99228, 0.919144, 0.328798],
			|      [0.18126, -0.00527, 0.10389, 0.860045, 0.916075, 0.328798],
			|      [0.182655, -0.05024, 0.059374, 0.582049, 0.893197, 0.328798],
			|      [0.184049, -0.09375, 0.055255, 0.202712, 0.893787, 0.328798],
			|      [0.185443, -0.07557, -0.034554, 0.214315, 0.870091, 0.328798],
			|      [0.186838, 0.06221, 0.114106, 0.596477, 0.865477, 0.328798],
			|      [0.188232, 0.10844, 0.198852, 0.874894, 0.866197, 0.328798],
			|      [0.189626, 0.06371, 0.147406, 0.997076, 0.863051, 0.328798],
			|      [0.191021, 0.09474, 0.179754, 0.937439, 0.85875, 0.348155],
			|      [0.192415, -0.06708, 0.072752, 0.703377, 0.857316, 0.348155],
			|      [0.193809, -0.07199, 0.046361, 0.33546, 0.855113, 0.348155],
			|      [0.195204, -0.07619, -0.045871, 0.098759, 0.860362, 0.348155],
			|      [0.196598, 0.01522, 0.03147, 0.516622, 0.860869, 0.348155],
			|      [0.197992, 0.07846, 0.154438, 0.835944, 0.853557, 0.348155],
			|      [0.199386, 0.04993, 0.109716, 0.991427, 0.855217, 0.348155],
			|      [0.200781, 0.06971, 0.125765, 0.948659, 0.84514, 0.338062],
			|      [0.202175, -0.02991, 0.046227, 0.712569, 0.847268, 0.338062],
			|      [0.203569, -0.08444, 0.030659, 0.328189, 0.835534, 0.338062],
			|      [0.204964, -0.02433, 0.010407, 0.126978, 0.810232, 0.338062],
			|      [0.206358, -0.00071, 0.071084, 0.557995, 0.810254, 0.338062],
			|      [0.207752, 0.08409, 0.048506, 0.872137, 0.814816, 0.338062],
			|      [0.209147, 0.0798, 0.162835, 0.999171, 0.823068, 0.338062],
			|      [0.210541, 0.00535, 0.060361, 0.907833, 0.815639, 0.348155],
			|      [0.211935, -0.04726, 0.031641, 0.614478, 0.814171, 0.348155],
			|      [0.21333, -0.05134, 0.013106, 0.181435, 0.760861, 0.348155],
			|      [0.214724, -0.03286, 0.00604, 0.295279, 0.725787, 0.348155],
			|      [0.216118, 0.05686, 0.111847, 0.70672, 0.728181, 0.348155],
			|      [0.217513, 0.10404, 0.167924, 0.955944, 0.749909, 0.348155],
			|      [0.218907, 0.02613, 0.142087, 0.98144, 0.735462, 0.348155],
			|      [0.220301, -0.04145, 0.07009, 0.773304, 0.741398, 0.348155],
			|      [0.221695, -0.07558, -0.040497, 0.37773, 0.743364, 0.348155],
			|      [0.22309, -0.04225, 0.004463, 0.111994, 0.726758, 0.348155],
			|      [0.224484, 0.07455, 0.075155, 0.576702, 0.72512, 0.348155],
			|      [0.225878, 0.12696, 0.171387, 0.900109, 0.601032, 0.348155],
			|      [0.227273, 0.01845, 0.087903, 0.998352, 0.573659, 0.338062],
			|      [0.228667, -0.04359, 0.026648, 0.84271, 0.547665, 0.338062],
			|      [0.230061, -0.0736, -0.041112, 0.469184, 0.51216, 0.338062],
			|      [0.231456, -0.01842, -0.038991, 0.028418, 0.543151, 0.338062],
			|      [0.23285, 0.07848, 0.068358, 0.521036, 0.528848, 0.338062],
			|      [0.234244, 0.10027, 0.244253, 0.877467, 0.509237, 0.338062],
			|      [0.235639, 0.00394, 0.060431, 0.999685, 0.487798, 0.328798],
			|      [0.237033, -0.00887, 0.050976, 0.850818, 0.508205, 0.328798],
			|      [0.238427, -0.0483, 0.050458, 0.467575, 0.419452, 0.328798],
			|      [0.239822, -0.01128, -0.019554, 0.047273, 0.384137, 0.328798],
			|      [0.241216, 0.0551, 0.139298, 0.551311, 0.383818, 0.328798],
			|      [0.24261, 0.03007, 0.217086, 0.901579, 0.364757, 0.328798],
			|      [0.244004, -0.00151, 0.23848, 0.995502, 0.335706, 0.328798],
			|      [0.245399, -0.01167, 0.000367, 0.802109, 0.301211, 0.328798],
			|      [0.246793, 0.033, -0.519545, 0.373639, 0.277102, 0.328798],
			|      [0.248187, 0.05297, 0.240839, 0.166967, 0.259858, 0.328798],
			|      [0.249582, 0.04838, 0.206489, 0.66031, 0.190925, 0.328798],
			|      [0.250976, 0.00418, -0.055811, 0.957294, 0.195026, 0.328798],
			|      [0.25237, -0.04358, -0.167614, 0.964897, 0.122212, 0.320256],
			|      [0.253765, -0.04168, -0.748016, 0.676672, 0.108723, 0.320256],
			|      [0.255159, -0.03513, -4.445745, 0.177691, 0.054795, 0.320256],
			|      [0.256553, 0.02522, 0.380111, 0.378997, 0.066148, 0.320256],
			|      [0.257948, 0.03408, 3.932869, 0.81852, 0.004424, 0.320256],
			|      [0.259342, 0.00307, 0.262246, 0.999236, -0.074315, 0.320256],
			|      [0.260736, -0.04969, -1.809499, 0.85949, -0.067976, 0.338062],
			|      [0.262131, 0.02857, 4.308165, 0.440279, -0.068699, 0.338062],
			|      [0.263525, 0.06553, -0.473114, 0.124864, -0.067305, 0.338062],
			|      [0.264919, 0.0623, -0.353712, 0.651095, -0.205266, 0.338062],
			|      [0.266313, 0.01117, -0.11934, 0.962368, -0.214936, 0.338062],
			|      [0.267708, -0.01286, 0.14858, 0.951084, -0.245936, 0.328798],
			|      [0.269102, -0.03874, 0.255293, 0.616849, -0.281086, 0.328798],
			|      [0.270496, 0.04772, 0.033847, 0.069972, -0.329287, 0.328798],
			|      [0.271891, 0.01702, -0.075283, 0.503271, -0.324254, 0.328798],
			|      [0.273285, -0.03383, 0.05396, 0.903339, -0.403336, 0.328798],
			|      [0.274679, -0.01219, 0.159969, 0.987308, -0.371262, 0.328798],
			|      [0.276074, 0.02672, 0.059181, 0.721397, -0.395923, 0.328798],
			|      [0.277468, 0.07005, 0.021814, 0.19636, -0.412709, 0.328798],
			|      [0.278862, 0.06884, -0.006977, 0.401545, -0.398249, 0.328798],
			|      [0.280257, -0.03376, 0.014865, 0.855602, -0.402531, 0.328798],
			|      [0.281651, -0.03459, 0.082925, 0.997402, -0.420537, 0.320256],
			|      [0.283045, 0.03321, 0.022949, 0.770647, -0.499549, 0.320256],
			|      [0.284439, 0.06106, -0.012179, 0.255501, -0.503012, 0.320256],
			|      [0.285834, 0.05797, 0.007429, 0.357656, -0.511295, 0.320256],
			|      [0.287228, -0.03362, -0.031083, 0.837414, -0.535663, 0.320256],
			|      [0.288622, -0.04525, 0.106823, 0.998692, -0.500527, 0.320256],
			|      [0.290017, -0.0013, 0.061603, 0.775572, -0.53106, 0.320256],
			|      [0.291411, 0.02401, 0.007261, 0.250273, -0.529988, 0.320256],
			|      [0.292805, 0.03158, -0.0427, 0.374915, -0.492185, 0.320256],
			|      [0.2942, -0.06419, 0.066845, 0.854294, -0.513345, 0.320256],
			|      [0.295594, -0.00165, 0.080702, 0.995408, -0.458904, 0.312348],
			|      [0.296988, 0.0588, 0.029095, 0.737825, -0.352077, 0.312348],
			|      [0.298383, 0.04955, -0.013968, 0.181162, -0.362077, 0.312348],
			|      [0.299777, -0.01521, 0.029032, 0.451178, -0.395473, 0.312348],
			|      [0.301171, -0.01885, 0.083324, 0.900503, -0.359714, 0.312348],
			|      [0.302566, 0.0584, 0.081797, 0.978953, -0.281781, 0.304997],
			|      [0.30396, 0.05921, -0.023391, 0.649771, -0.283945, 0.304997],
			|      [0.305354, 0.0106, 0.007442, 0.046743, -0.214707, 0.304997],
			|      [0.306748, -0.03111, 0.063853, 0.578041, -0.20083, 0.304997],
			|      [0.308143, -0.02964, 0.153145, 0.958574, -0.152581, 0.304997],
			|      [0.309537, 0.0244, 0.065102, 0.928913, -0.136445, 0.304997],
			|      [0.310931, 0.05315, -0.088078, 0.497545, -0.147562, 0.304997],
			|      [0.312326, -0.00277, 0.036986, 0.15187, -0.122952, 0.304997],
			|      [0.31372, 0.01278, 0.044876, 0.736717, -0.070328, 0.304997],
			|      [0.315114, 0.05846, -0.032209, 0.997932, -0.037239, 0.304997],
			|      [0.316509, 0.01105, -0.079761, 0.815938, -0.041666, 0.328798],
			|      [0.317903, -0.03362, -0.428825, 0.26777, 0.034438, 0.328798],
			|      [0.319297, -0.01492, -0.564715, 0.402907, 0.060134, 0.328798],
			|      [0.320692, 0.02327, 0.045048, 0.892536, 0.070803, 0.328798],
			|      [0.322086, -0.00417, 0.073112, 0.975266, 0.013645, 0.298142],
			|      [0.32348, -0.01113, -0.027945, 0.608937, 0.035687, 0.298142],
			|      [0.324875, -0.06977, -0.065691, 0.04094, 0.049266, 0.298142],
			|      [0.326269, -0.00778, -0.035581, 0.673607, 0.07073, 0.298142],
			|      [0.327663, 0.06069, 0.025653, 0.991414, 0.071884, 0.298142],
			|      [0.329057, 0.02042, 0.091822, 0.840874, 0.194353, 0.320256],
			|      [0.330452, 0.01451, -0.010134, 0.289236, 0.243666, 0.320256],
			|      [0.331846, -0.00434, 0.008531, 0.4027, 0.200089, 0.320256],
			|      [0.33324, 0.06952, 0.061677, 0.902181, 0.249229, 0.320256],
			|      [0.334635, 0.02037, 0.17701, 0.964635, 0.171087, 0.312348],
			|      [0.336029, -0.03694, -0.027828, 0.555446, 0.12804, 0.312348],
			|      [0.337423, -0.01124, 0.033224, 0.128306, 0.165139, 0.312348],
			|      [0.338818, 0.05357, 0.057768, 0.750362, 0.094546, 0.312348],
			|      [0.340212, 0.00274, 0.080429, 1.0, -0.027832, 0.285714],
			|      [0.341606, -0.02674, 0.022943, 0.748564, -0.006744, 0.285714],
			|      [0.343001, 0.03294, 0.008151, 0.11851, 0.027324, 0.285714],
			|      [0.344395, 0.06766, 0.192414, 0.573425, 0.00907, 0.285714],
			|      [0.345789, 0.01039, 0.042662, 0.974057, 0.014979, 0.285714],
			|      [0.347183, -0.02291, 0.097428, 0.874738, 0.024548, 0.304997],
			|      [0.348578, 0.00992, -0.074449, 0.322477, 0.033227, 0.304997],
			|      [0.349972, 0.01284, -0.637943, 0.399083, 0.008562, 0.304997],
			|      [0.351366, -0.02091, -0.131829, 0.913468, -0.007086, 0.304997],
			|      [0.352761, -0.02255, 0.101823, 0.94782, -0.032375, 0.304997],
			|      [0.354155, 0.02277, 0.060811, 0.479852, 0.019922, 0.304997],
			|      [0.355549, 0.03393, 0.015595, 0.245151, -0.033741, 0.304997],
			|      [0.356944, 0.01118, -0.033188, 0.840461, -0.004259, 0.304997],
			|      [0.358338, -0.01654, 0.051401, 0.983827, 0.052143, 0.298142],
			|      [0.359732, 0.04888, 0.039724, 0.593704, 0.056632, 0.298142],
			|      [0.361127, 0.02066, -0.020052, 0.121355, 0.070279, 0.298142],
			|      [0.362521, 0.01076, 0.060818, 0.771536, 0.042969, 0.298142],
			|      [0.363915, 0.01239, 0.141158, 0.997387, 0.073112, 0.298142],
			|      [0.36531, 0.05255, 0.03486, 0.670114, 0.075518, 0.298142],
			|      [0.366704, 0.02024, -0.011288, 0.03199, 0.077319, 0.298142],
			|      [0.368098, -0.01806, 0.07606, 0.717776, 0.068344, 0.298142],
			|      [0.369492, -0.01371, 0.080181, 1.0, 0.103817, 0.26968],
			|      [0.370887, 0.00812, 0.054114, 0.71532, 0.090053, 0.26968],
			|      [0.372281, 0.06555, -0.002195, 0.021804, 0.113834, 0.26968],
			|      [0.373675, 0.01644, -0.022212, 0.685786, 0.09347, 0.26968],
			|      [0.37507, 0.00455, 0.055679, 0.999373, 0.114851, 0.26968],
			|      [0.376464, -0.02311, 0.040987, 0.734085, 0.01756, 0.29173],
			|      [0.377858, -0.01443, -0.162105, 0.040234, 0.054214, 0.29173],
			|      [0.379253, -0.01423, -0.234953, 0.678708, 0.064874, 0.29173],
			|      [0.380647, 0.04026, 0.040582, 0.999339, 0.088496, 0.29173],
			|      [0.382041, 0.03177, 0.079701, 0.728803, 0.094073, 0.285714],
			|      [0.383436, 0.00782, 0.003887, 0.023815, 0.076774, 0.285714],
			|      [0.38483, -0.0091, 0.023832, 0.6969, 0.123197, 0.285714],
			|      [0.386224, 0.04341, 0.143896, 0.999996, 0.102406, 0.285714],
			|      [0.387619, -0.00715, 0.165134, 0.699235, 0.114474, 0.320256],
			|      [0.389013, -0.02281, 0.034086, 0.026954, 0.194945, 0.320256],
			|      [0.390407, 0.03967, 0.062031, 0.738166, 0.198451, 0.320256],
			|      [0.391801, 0.04512, 0.166633, 0.997885, 0.113669, 0.280056],
			|      [0.393196, -0.00469, 0.053384, 0.642799, 0.093466, 0.280056],
			|      [0.39459, -0.05721, -0.040038, 0.111315, 0.136823, 0.280056],
			|      [0.395984, 0.0397, 0.057285, 0.7976, 0.168794, 0.280056],
			|      [0.397379, 0.047, 0.113182, 0.986195, 0.21502, 0.280056],
			|      [0.398773, 0.04184, 0.086288, 0.555148, 0.236642, 0.280056],
			|      [0.400167, -0.02543, -0.025957, 0.227414, 0.268745, 0.280056],
			|      [0.401562, 0.00595, 0.062637, 0.867146, 0.275549, 0.280056],
			|      [0.402956, -0.00278, 0.000796, 0.955132, 0.277829, 0.274721],
			|      [0.40435, 0.02173, 0.023337, 0.431388, 0.307112, 0.274721],
			|      [0.405745, 0.06515, 0.091046, 0.370981, 0.320327, 0.274721],
			|      [0.407139, -0.00504, 0.008747, 0.934976, 0.321335, 0.274721],
			|      [0.408533, -0.02533, -0.029845, 0.89269, 0.322322, 0.274721],
			|      [0.409927, 0.02141, -0.061735, 0.267845, 0.371125, 0.274721],
			|      [0.411322, 0.00889, -0.050504, 0.533671, 0.373283, 0.274721],
			|      [0.412716, 0.04813, 0.028876, 0.985286, 0.377574, 0.274721],
			|      [0.41411, -0.05391, 0.003158, 0.786122, 0.38353, 0.304997],
			|      [0.415505, 0.01746, 0.008398, 0.064437, 0.386334, 0.304997],
			|      [0.416899, 0.00888, 0.024782, 0.701488, 0.365445, 0.304997],
			|      [0.418293, 0.02035, 0.05639, 0.99872, 0.331931, 0.26968],
			|      [0.419688, 0.01731, -0.049852, 0.624379, 0.337289, 0.26968],
			|      [0.421082, 0.06617, -0.008902, 0.172559, 0.349725, 0.26968],
			|      [0.422476, 0.02064, 0.034931, 0.853741, 0.329702, 0.26968],
			|      [0.423871, 0.01143, -0.023516, 0.95416, 0.333459, 0.264906],
			|      [0.425265, -0.00213, 0.049273, 0.401685, 0.342725, 0.264906],
			|      [0.426659, -0.02579, -0.011479, 0.427794, 0.358102, 0.264906],
			|      [0.428054, -0.02084, -0.020138, 0.963376, 0.355925, 0.264906],
			|      [0.429448, 0.0194, 0.113783, 0.832226, 0.210189, 0.298142],
			|      [0.430842, -0.02029, -0.05883, 0.1218, 0.202298, 0.298142],
			|      [0.432236, -0.00915, 0.037719, 0.674854, 0.198459, 0.298142],
			|      [0.433631, -0.02372, 0.037244, 0.999398, 0.258899, 0.260378],
			|      [0.435025, 0.00409, -0.081933, 0.62062, 0.223988, 0.260378],
			|      [0.436419, 0.09055, -0.044427, 0.197521, 0.178819, 0.260378],
			|      [0.437814, 0.04633, 0.107999, 0.876745, 0.193776, 0.260378],
			|      [0.439208, 0.0609, 0.055976, 0.932231, 0.171875, 0.260378],
			|      [0.440602, 0.01793, 0.123218, 0.320939, 0.177173, 0.260378],
			|      [0.441997, 0.01559, 0.019099, 0.521727, 0.152437, 0.260378],
			|      [0.443391, -0.06893, -0.039578, 0.989572, 0.154228, 0.260378],
			|      [0.444785, -0.00426, -0.023969, 0.742083, 0.173523, 0.285714],
			|      [0.44618, 0.04879, -0.029467, 0.044718, 0.167093, 0.285714],
			|      [0.447574, -0.04816, -0.036555, 0.800077, 0.14596, 0.285714],
			|      [0.448968, -0.10018, -0.003775, 0.97109, 0.187242, 0.256074],
			|      [0.450363, -0.05061, -0.08062, 0.429208, 0.152094, 0.256074],
			|      [0.451757, 0.03138, -0.047691, 0.429792, 0.139348, 0.256074],
			|      [0.453151, 0.04584, 0.00792, 0.972063, 0.123766, 0.256074],
			|      [0.454545, 0.07718, -0.0124, 0.793444, 0.137757, 0.285714],
			|      [0.45594, 0.07741, 0.052201, 0.023424, 0.106644, 0.285714],
			|      [0.457334, 0.06833, 0.112496, 0.76513, 0.088494, 0.285714],
			|      [0.458728, 0.0521, 0.075129, 0.980696, 0.120236, 0.251976],
			|      [0.460123, 0.08535, -0.019624, 0.45818, 0.133636, 0.251976],
			|      [0.461517, -0.00512, 0.049874, 0.411161, 0.124952, 0.251976],
			|      [0.462911, -0.10263, 0.033327, 0.969959, 0.127765, 0.251976],
			|      [0.464306, -0.00841, 0.023007, 0.791674, 0.159173, 0.280056],
			|      [0.4657, -0.08718, -0.065555, 0.008958, 0.121392, 0.280056],
			|      [0.467094, -0.08106, -0.095788, 0.781641, 0.067387, 0.280056],
			|      [0.468489, 0.00503, -0.210853, 0.972673, 0.129794, 0.248069],
			|      [0.469883, 0.17087, -0.010508, 0.414178, 0.129794, 0.248069],
			|      [0.471277, -0.01939, 0.038752, 0.465298, 0.129794, 0.248069],
			|      [0.472671, -0.0247, 0.06299, 0.984861, 0.129794, 0.248069],
			|      [0.474066, -0.01745, -0.161078, 0.738498, 0.124677, 0.274721],
			|      [0.47546, 0.11544, 0.036969, 0.084784, 0.124677, 0.274721],
			|      [0.476854, 0.07788, -0.133492, 0.842632, 0.124677, 0.274721],
			|      [0.478249, 0.0782, 0.205442, 0.9393, 0.150909, 0.244339],
			|      [0.479643, 0.06055, 0.049043, 0.295746, 0.150909, 0.244339],
			|      [0.481037, 0.02691, -0.099333, 0.582355, 0.150909, 0.244339],
			|      [0.482432, -0.05171, -0.111495, 0.999667, 0.150909, 0.244339],
			|      [0.483826, -0.06475, -0.133263, 0.62232, 0.12527, 0.26968],
			|      [0.48522, -0.04826, -0.141682, 0.251915, 0.12527, 0.26968],
			|      [0.486615, 0.10069, 0.08775, 0.925555, 0.12527, 0.26968],
			|      [0.488009, -0.0628, -0.146584, 0.857182, 0.12527, 0.26968],
			|      [0.489403, -0.02172, 0.047867, 0.098824, 0.12527, 0.26968],
			|      [0.490798, 0.10219, -0.022228, 0.74011, 0.12527, 0.26968],
			|      [0.492192, -0.06966, -0.060678, 0.980835, 0.155524, 0.237356],
			|      [0.493586, 0.03215, 0.122677, 0.425577, 0.155524, 0.237356],
			|      [0.49498, -0.07823, 0.003565, 0.476697, 0.155524, 0.237356],
			|      [0.496375, -0.01839, 0.231056, 0.990768, 0.155524, 0.237356],
			|      [0.497769, -0.06407, 0.077315, 0.694306, 0.111057, 0.264906],
			|      [0.499163, 0.0084, -0.166847, 0.172368, 0.111057, 0.264906],
			|      [0.500558, -0.0355, 0.029741, 0.898041, 0.111057, 0.264906],
			|      [0.501952, -0.18627, 0.067689, 0.883296, 0.111057, 0.264906],
			|      [0.503346, -0.19161, 0.074086, 0.137629, 0.111057, 0.264906],
			|      [0.504741, -0.19166, 0.117977, 0.722954, 0.111057, 0.264906]
			|    ],
			|    "xf": [
			|      [1.0, 0.0, 0.0, 1.0, -11.3158636, -5.5245646],
			|      [1.0, 0.0, 0.0, 1.0, -6.2875964, -3.1627418],
			|      [1.0, 0.0, 0.0, 1.0, -3.9246664, -1.7908946],
			|      [1.0, 0.0, 0.0, 1.0, -3.0328272, -1.450459],
			|      [1.0, 0.0, 0.0, 1.0, -2.693171, -1.5196828],
			|      [1.0, 0.0, 0.0, 1.0, -2.7970028, -1.5139418],
			|      [1.0, 0.0, 0.0, 1.0, -2.1833664, -1.1531354],
			|      [1.0, 0.0, 0.0, 1.0, -1.8287336, -0.9515636],
			|      [1.0, 0.0, 0.0, 1.0, -1.7717082, -0.8775596],
			|      [1.0, 0.0, 0.0, 1.0, -1.4500136, -0.7974658],
			|      [1.0, 0.0, 0.0, 1.0, -1.099411, -0.8224452],
			|      [1.0, 0.0, 0.0, 1.0, -0.7963594, -0.7767876],
			|      [1.0, 0.0, 0.0, 1.0, -0.9365164, -0.6369768],
			|      [1.0, 0.0, 0.0, 1.0, -0.510544, -0.6018484],
			|      [1.0, 0.0, 0.0, 1.0, -0.5530062, -0.4824782],
			|      [1.0, 0.0, 0.0, 1.0, -0.4297236, -0.2444848],
			|      [1.0, 0.0, 0.0, 1.0, -0.1995126, -0.0893422],
			|      [1.0, 0.0, 0.0, 1.0, -0.0475556, 0.0668756],
			|      [1.0, 0.0, 0.0, 1.0, -0.1147278, 0.1408094],
			|      [1.0, 0.0, 0.0, 1.0, -0.002348, 0.0497622],
			|      [1.0, 0.0, 0.0, 1.0, -0.159925, -0.0111622],
			|      [1.0, 0.0, 0.0, 1.0, -0.0321512, -0.016534],
			|      [1.0, 0.0, 0.0, 1.0, 0.144519, 0.1019704],
			|      [1.0, 0.0, 0.0, 1.0, -0.0292126, 0.0963178],
			|      [1.0, 0.0, 0.0, 1.0, 0.0595374, 0.0925296],
			|      [1.0, 0.0, 0.0, 1.0, 0.0, 0.0],
			|      [1.0, 0.0, 0.0, 1.0, -0.0115924, -0.0895632],
			|      [1.0, 0.0, 0.0, 1.0, 0.0478666, 0.0762326],
			|      [1.0, 0.0, 0.0, 1.0, 0.1624472, 0.2090144],
			|      [1.0, 0.0, 0.0, 1.0, 0.0720868, 0.2751882],
			|      [1.0, 0.0, 0.0, 1.0, 0.1288258, 0.3548378],
			|      [1.0, 0.0, 0.0, 1.0, 0.380294, 0.3371952],
			|      [1.0, 0.0, 0.0, 1.0, 0.3512738, 0.1356072],
			|      [1.0, 0.0, 0.0, 1.0, 0.514599, 0.1653124],
			|      [1.0, 0.0, 0.0, 1.0, 0.315582, 0.0229408],
			|      [1.0, 0.0, 0.0, 1.0, 0.3885864, -0.2098354],
			|      [1.0, 0.0, 0.0, 1.0, 0.6903336, -0.0021272],
			|      [1.0, 0.0, 0.0, 1.0, 0.852611, 0.068653],
			|      [1.0, 0.0, 0.0, 1.0, 0.547251, 0.040934],
			|      [1.0, 0.0, 0.0, 1.0, 0.614293, -0.049351],
			|      [1.0, 0.0, 0.0, 1.0, 0.5482666, -0.0736904],
			|      [1.0, 0.0, 0.0, 1.0, 0.2716996, -0.1215574],
			|      [1.0, 0.0, 0.0, 1.0, 0.470437, -0.1337794],
			|      [1.0, 0.0, 0.0, 1.0, 0.5977998, -0.2263616],
			|      [1.0, 0.0, 0.0, 1.0, 0.4179902, -0.2259428],
			|      [1.0, 0.0, 0.0, 1.0, 0.172004, -0.2223648],
			|      [1.0, 0.0, 0.0, 1.0, 0.0174404, -0.269382],
			|      [1.0, 0.0, 0.0, 1.0, 0.0639402, -0.1386054],
			|      [1.0, 0.0, 0.0, 1.0, 0.3090576, -0.0726228],
			|      [1.0, 0.0, 0.0, 1.0, -0.096445, 0.006879]
			|    ],
			|    "boxx": [
			|      [1134.0, 108.0, 0.0, 0.0, 1, 0],
			|      [822.0, 204.0, 0.0, 0.0, 1, 0],
			|      [990.0, 210.0, 0.0, 0.0, 1, 0],
			|      [576.0, 288.0, 0.0, 0.0, 1, 0],
			|      [732.0, 294.0, 0.0, 0.0, 1, 0],
			|      [690.0, 432.0, 0.0, 0.0, 1, 0],
			|      [426.0, 438.0, 0.0, 0.0, 1, 0],
			|      [546.0, 456.0, 0.0, 0.0, 1, 0],
			|      [1110.0, 510.0, 0.0, 0.0, 1, 0],
			|      [588.0, 558.0, 0.0, 0.0, 1, 0],
			|      [432.0, 570.0, 0.0, 0.0, 1, 0],
			|      [264.0, 582.0, 0.0, 0.0, 1, 0],
			|      [708.0, 672.0, 0.0, 0.0, 1, 0],
			|      [528.0, 732.0, 0.0, 0.0, 1, 0],
			|      [228.0, 738.0, 0.0, 0.0, 1, 0],
			|      [396.0, 744.0, 0.0, 0.0, 1, 0],
			|      [816.0, 750.0, 0.0, 0.0, 1, 0],
			|      [1092.0, 804.0, 0.0, 0.0, 1, 0],
			|      [738.0, 870.0, 0.0, 0.0, 1, 0],
			|      [450.0, 876.0, 0.0, 0.0, 1, 0],
			|      [918.0, 894.0, 0.0, 0.0, 1, 0],
			|      [1056.0, 966.0, 0.0, 0.0, 1, 0],
			|      [408.0, 978.0, 0.0, 0.0, 1, 0],
			|      [936.0, 1044.0, 0.0, 0.0, 1, 0],
			|      [120.0, 1074.0, 0.0, 0.0, 1, 0],
			|      [804.0, 1074.0, 0.0, 0.0, 1, 0]
			|    ]
			|  },
			|  "id": 1,
			|  "token": "DN0DXlWz2H3rRRoxr3cX5lUXNdlT51StHz6xjkajlhVtTmTwFnSBAFCGbDlD4HXYV2u6VrZLFFcQbbdaW33jhF"
			|}
		""".trimMargin()

		val p = Profiler()

		for (i in 0 until size) {

			val json = p.time("parse") {
				ObjectMapper().readTree(jsonstr)
			}

			val params = p.time("params") {
				json.asObject().getObject("params")
					?: JsonRpc.nodes.objectNode()
			}

			val micrographId = "synthetic_$i"

			p.time("write") {
				Database.instance.micrographs.write(job.idOrThrow, micrographId) {
					p.time("doc") {
						set("jobId", job.idOrThrow)
						set("micrographId", micrographId)
						set("timestamp", Instant.now().toEpochMilli())
						p.time("ctf") {
							params.getArray("ctf")?.let { set("ctf", CTF.from(it).toDoc()) }
						}
						p.time("avgrot") {
							params.getArray("avgrot")?.let { set("avgrot", AVGROT.from(it).toDoc()) }
						}
						p.time("xf") {
							params.getArray("xf")?.let { set("xf", XF.from(it).toDoc()) }
						}
						p.time("boxx") {
							params.getArray("boxx")?.let { set("boxx", BOXX.from(it).toDoc()) }
						}
					}
				}
			}
		}

		return p.toString()
	}
}
