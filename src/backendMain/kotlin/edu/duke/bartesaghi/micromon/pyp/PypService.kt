package edu.duke.bartesaghi.micromon.pyp

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.mongodb.client.model.Updates.set
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.CommandsGrid
import edu.duke.bartesaghi.micromon.cluster.CommandsScript
import edu.duke.bartesaghi.micromon.files.*
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.sessions.Session
import edu.duke.bartesaghi.micromon.sessions.SingleParticleSession
import edu.duke.bartesaghi.micromon.sessions.TomographySession
import io.ktor.features.*
import io.ktor.routing.Routing
import java.time.Instant
import java.text.NumberFormat
import java.util.Locale


// this warning is incorrect here, the linter is probably confused by the reflection
@Suppress("RedundantSuspendModifier")
object PypService {

	fun init(routing: Routing) {
		JsonRpc.init(
			routing,
			endpoint = "pyp",
			methods = mapOf(
				"ping" to ::ping,
				// NOTE: these are named "slurm_x" for historical reasons
				"slurm_started" to ::clusterJobStarted,
				"slurm_ended" to ::clusterJobEnded,
				"failed" to ::clusterJobFailed,
				"slurm_sbatch" to ::submitClusterJob,
				"write_parameters" to ::writeParameters,
				"write_micrograph" to ::writeMicrograph,
				"write_reconstruction" to ::writeReconstruction,
				"write_tiltseries" to ::writeTiltSeries,
				"write_refinement" to ::writeRefinement,
				"write_refinement_bundle" to ::writeRefinementBundle,
				"write_classes" to ::writeClasses,
				"log" to ::log
			)
		)
	}

	// mostly just for testing
	suspend fun ping(params: ObjectNode): JsonRpcResponse {

		return JsonRpcSuccess(JsonRpc.nodes.textNode("pong"))
	}

	suspend fun clusterJobStarted(params: ObjectNode): JsonRpcResponse {

		Cluster.started(
			params.getStringOrThrow("webid"),
			params.getInt("arrayid")
		)

		return JsonRpcSuccess()
	}

	suspend fun clusterJobEnded(params: ObjectNode): JsonRpcResponse {

		Cluster.ended(
			params.getStringOrThrow("webid"),
			params.getInt("arrayid"),
			params.get("exit_code")
				?.takeIf { it.isIntegralNumber }
				?.intValue()
		)

		return JsonRpcSuccess()
	}

	suspend fun clusterJobFailed(params: ObjectNode): JsonRpcResponse {

		val clusterJobId = params.getStringOrThrow("webid")
		val arrayId = params.getInt("arrayid")

		ClusterJob.get(clusterJobId)
			?.pushFailure(ClusterJob.FailureEntry(), arrayId)

		return JsonRpcSuccess()
	}

	suspend fun submitClusterJob(params: ObjectNode): JsonRpcResponse {

		// look up the parent cluster job info
		val parent = ClusterJob.get(params.getStringOrThrow("webid"))
			?: return JsonRpcFailure("unrecognized webid")

		// if the parent job was canceled, don't allow the new submission
		if (parent.getLog()?.wasCanceled() == true) {
			return JsonRpcFailure("Cluster job can't be submitted because parent job was canceled.")
		}

		// submit the new job to the cluster
		val clusterJob = ClusterJob(
			containerId = parent.containerId,
			ownerId = parent.ownerId,
			ownerListener = parent.ownerListener,
			webName = params.getStringOrThrow("web_name"),
			clusterName = params.getStringOrThrow("cluster_name"),
			commands = params.getObjectOrThrow("commands").let { commands ->
				when (val type = commands.getStringOrThrow("type")) {

					"script" -> CommandsScript(
						commands.getStringsOrThrow("commands"),
						commands.getInt("array_size"),
						commands.getInt("bundle_size")
					)

					"grid" -> CommandsGrid(
						commands.getArrayOrThrow("commands").let { array ->
							array.indices().map { i ->
								array.getStringsOrThrow(i, "grid commands")
							}
						},
						commands.getInt("bundle_size")
					)

					else -> throw BadRequestException("invalid commands type: $type")
				}
			},
			dir = params.getStringOrThrow("dir").toPath(),
			env = params.getArrayOrThrow("env").let { vars ->
				vars.indices().map { i ->
					val (name, value) = vars.getStringsOrThrow(i, "env[$i]")
					ClusterJob.EnvVar(name, value)
				}
			},
			args = params.getStrings("args") ?: emptyList(),
			deps = params.getStrings("deps") ?: emptyList()
		)
		val clusterJobId = try {
			clusterJob.submit()
		} catch (ex: ClusterJob.LaunchFailedException) {
			return JsonRpcFailure("Cluster job submission failed:\n${ex.reason}:\n${ex.console.joinToString("\n")}")
		}

		if (clusterJobId == null) {
			return JsonRpcFailure("Cluster job submission was canceled")
		}

		// return the id, to use for dependencies
		return JsonRpcSuccess(JsonRpc.nodes.textNode(clusterJobId))
	}

	suspend fun writeParameters(params: ObjectNode): JsonRpcResponse {

		// lookup the job owner
		val clusterJob = ClusterJob.get(params.getStringOrThrow("webid"))
			?: return JsonRpcFailure("invalid webid")
		val owner = clusterJob.findOwner()

		/* parse the inputs, PYP sends something like:
			"webid" : "eFZHeeCNEQJp5W2B",
			"parameter_id" : "sp-preprocessing-eFZHdEHe66FQQNp8",
			"parameters" : {
				"scope_pixel" : 1.0,
				"scope_voltage" : 300.0,
				...
			}
		 */
		// NOTE: I don't think we need the parameter_id, it seems to be redundant if we already have the webid
		val pypParams = params.getObjectOrThrow("parameters")
		val values = ArgValues(Backend.pypArgs)
		for (key in pypParams.fieldNames()) {
			val arg = values.args.arg(key)
				?: throw BadRequestException("unrecognized parameter: $key. Is this defined in the PYP parameters configuration file?")
			values[arg] = when (arg.type) {
				is ArgType.TBool -> pypParams.getBool(key)
				is ArgType.TEnum -> pypParams.getString(key)
				is ArgType.TFloat -> pypParams.getDouble(key)
				is ArgType.TFloat2 -> pypParams.getArray(key)?.let { array ->
					array.getDoubleOrThrow(0, key) to array.getDoubleOrThrow(1, key)
				}
				is ArgType.TInt -> pypParams.getInt(key)
				is ArgType.TPath -> pypParams.getString(key)
				is ArgType.TStr -> pypParams.getString(key)
			}
		}

		// update the database
		Database.parameters.writeParams(owner.id, values)

		// notify any listening clients
		when (owner) {

			is Owner.Job -> {
				when (owner.job) {

					is SingleParticlePreprocessingJob -> SingleParticlePreprocessingJob.eventListeners.sendParams(owner.job.idOrThrow, values)
					is TomographyPreprocessingJob -> TomographyPreprocessingJob.eventListeners.sendParams(owner.job.idOrThrow, values)

					is SingleParticleCoarseRefinementJob,
					is SingleParticleFineRefinementJob,
					is SingleParticleFlexibleRefinementJob,
					is TomographyCoarseRefinementJob,
					is TomographyFineRefinementJob,
					is TomographyMovieCleaningJob,
					is TomographyFlexibleRefinementJob -> RefinementJobs.eventListeners.sendParams(owner.job.idOrThrow, values)

					else -> Unit // ignore other jobs
				}
			}

			is Owner.Session -> {
				owner.session.type.events.getListeners(owner.id)
					.forEach { it.onParams?.invoke(values) }
			}
		}

		return JsonRpcSuccess()
	}

	suspend fun writeMicrograph(params: ObjectNode): JsonRpcResponse {

		// lookup the job owner
		val clusterJob = ClusterJob.get(params.getStringOrThrow("webid"))
			?: return JsonRpcFailure("invalid webid")
		val owner = clusterJob.findOwner()

		// parse the inputs
		// NOTE: all these inputs are always sent by pyp
		val ctf = CTF.from(params.getArrayOrThrow("ctf"))
		val xf = XF.from(params.getArrayOrThrow("xf"))
		val avgrot = AVGROT.from(params.getArrayOrThrow("avgrot"))

		fun ArrayNode.readParticles(): Map<Int,Particle2D> {

			// pyp doesn't send size info with the particle coords, so lookup the radius from the pyp arguments
			val imagesScale = when (owner) {
				is Owner.Job -> owner.job.pypParameters()?.let { owner.job.imagesScale(it) }
				is Owner.Session -> owner.session.pypParameters()?.let { owner.session.imagesScale(it) }
			} ?: ImagesScale.default()
			val r = imagesScale.particleRadiusUnbinned

			return indices().associate { i ->

				val jsonParticle = getArrayOrThrow(i, "Particles coordinates")

				// read the x,y coords
				// NOTE: w,h components have no information and are always zero
				// NOTE: these particles come from pyp in unbinned coordinates
				val x = jsonParticle.getDoubleOrThrow(0, "Particles coordinates [$i].x")
				val y = jsonParticle.getDoubleOrThrow(1, "Particles coordinates [$i].y")

				val particleId = i + 1

				particleId to Particle2D(x, y, r)
			}
		}
		val particles = params.getArray("boxx")
			?.readParticles()

		// update the database
		val micrographId = params.getStringOrThrow("micrograph_id")
		Database.micrographs.write(owner.id, micrographId) {
			set("jobId", owner.id)
			set("micrographId", micrographId)
			set("timestamp", Instant.now().toEpochMilli())
			set("particleCount", particles?.size)
			set("ctf", ctf.toDoc())
			set("xf", xf.toDoc())
		}
		Database.micrographsAvgRot.write(owner.id, micrographId, avgrot)

		if (particles != null) {
			val list = ParticlesList.autoParticles2D(owner.id)
			Database.particleLists.createIfNeeded(list)
			Database.particles.importParticles2D(list.ownerId, list.name, micrographId, particles)
		}

		when (owner) {

			is Owner.Job -> {

				// update the job with the newest micrograph id
				val jobDoc = Database.jobs.get(owner.id)
				if (jobDoc != null) {

					val isFirstMicrograph = jobDoc["latestMicrographId"] == null
					Database.jobs.update(owner.id,
						set("latestMicrographId", micrographId)
					)

					// send the update to the clients, if needed
					if (isFirstMicrograph) {
						// reload the job so it gets the micrograph id we just wrote
						val job = Job.fromIdOrThrow(owner.job.idOrThrow)
						val jobData = job.data()
						Backend.projectEventListeners.getAll(job.userId, job.projectId)
							.forEach { it.onJobUpdate(jobData) }
					}
				}

				// notify any listening clients
				SingleParticlePreprocessingJob.eventListeners.sendMicrograph(owner.id, micrographId)
			}

			is Owner.Session -> {

				// notify any listening clients
				SingleParticleSession.events.getListeners(owner.id)
					.forEach { it.onMicrograph?.invoke(micrographId) }
			}
		}

		return JsonRpcSuccess()
	}

	suspend fun writeReconstruction(params: ObjectNode): JsonRpcResponse {

		// lookup the job owner
		val clusterJob = ClusterJob.get(params.getStringOrThrow("webid"))
			?: return JsonRpcFailure("invalid webid")
		val owner = clusterJob.findOwner()

		// parse micrograph params and update the database
		val reconstructionId = params.getStringOrThrow("reconstruction_id")
		val classNum = params.getIntOrThrow("class_num")
		val iteration = params.getIntOrThrow("iteration")
		val fsc = params.getArrayOrThrow("fsc")
		val metadata = ReconstructionMetaData.fromJson(params.getObjectOrThrow("metadata"))
		val plots = ReconstructionPlots.fromJson(params.getObjectOrThrow("plots"))

		Database.reconstructions.write(owner.id, reconstructionId) {
			set("jobId", owner.id)
			set("reconstructionId", reconstructionId)
			set("timestamp", Instant.now().toEpochMilli())
			set("classNum", classNum)
			set("iteration", iteration)
			set("metadata", metadata.toDoc())
			set("fsc", fsc.toListOfListOfDoubles())
			set("plots", plots.toDoc())
		}

		when (owner) {

			is Owner.Job -> {

				// update the job with the newest reconstruction info
				val formatter = NumberFormat.getIntegerInstance(Locale.US)
				val particlesUsed = formatter.format(metadata.particlesUsed.toInt())
				val particlesTotal = formatter.format(metadata.particlesTotal.toInt())
				Database.jobs.update(owner.id,
					set("latestReconstructionId", reconstructionId),
					// TODO: we should store structured data in the database, and do presentation formatting in the UI
					set("jobInfoString", "Iteration $iteration: $particlesUsed from $particlesTotal projections")
				)

				// update the job on the client
				// (reload the job so it gets the latest reconstruction we just wrote)
				val job = Job.fromIdOrThrow(owner.job.idOrThrow)
				val jobData = job.data()
				Backend.projectEventListeners.getAll(job.userId, job.projectId)
					.forEach { it.onJobUpdate(jobData) }

				// notify any listening clients
				RefinementJobs.eventListeners.sendReconstruction(owner.id, reconstructionId)
			}

			is Owner.Session -> {

				// TODO: notify any listening clients
				// ! Need to figure out if it's a SingleParticle or Tomography Session...
			}
		}

		return JsonRpcSuccess()
	}

	suspend fun writeTiltSeries(params: ObjectNode): JsonRpcResponse {

		// lookup the job/session
		val clusterJob = ClusterJob.get(params.getStringOrThrow("webid"))
			?: return JsonRpcFailure("invalid webid")
		val owner = clusterJob.findOwner()

		// parse the inputs
		// NOTE: all these inputs are always sent by pyp
		val ctf = CTF.from(params.getArrayOrThrow("ctf"))
		val xf = XF.from(params.getArrayOrThrow("xf"))
		val avgrot = AVGROT.from(params.getArrayOrThrow("avgrot"))
		val metadata = params.getObjectOrThrow("metadata")
		val dmd = DMD.from(metadata)

		// these inputs are sometimes sent by pyp
		fun ArrayNode.readParticles(): Map<Int,Particle3D> =
			indices().associate { i ->
				val jsonParticle = getArrayOrThrow(i, "Particles coordinates")
				val particleId = i + 1
				// NOTE: these particles come from pyp in binned coordinates
				val particle = Particle3D(
					x = jsonParticle.getDoubleOrThrow(0, "Particles coordinates [$i].x"),
					y = jsonParticle.getDoubleOrThrow(1, "Particles coordinates [$i].y"),
					z = jsonParticle.getDoubleOrThrow(2, "Particles coordinates [$i].z"),
					r = jsonParticle.getDoubleOrThrow(3, "Particles coordinates [$i].radius"),
				)
				particleId to particle
			}
		fun ArrayNode.readVirionThresholds(): Map<Int,Int> =
			indices().associate { i ->
				val virionId = i + 1
				val threshold = getArrayOrThrow(i, "Particles coordinates")
					.get(4)
					?.takeIf { it.isNumber }
					?.intValue()
					?: 1 // if pyp doesn't send a threshold, pick a default
				virionId to threshold
			}
		val virionsAndThresholds = metadata.getArray("virion_coordinates")
			?.let { it.readParticles() to it.readVirionThresholds() }
		val particles = metadata.getArray("spike_coordinates")
			?.readParticles()

		// update the database
		val tiltSeriesId = params.getStringOrThrow("tiltseries_id")
		Database.tiltSeries.write(owner.id, tiltSeriesId) {
			set("jobId", owner.id)
			set("tiltSeriesId", tiltSeriesId)
			set("timestamp", Instant.now().toEpochMilli())
			set("particleCount", particles?.size)
			set("ctf", ctf.toDoc())
			set("xf", xf.toDoc())
		}
		Database.tiltSeriesAvgRot.write(owner.id, tiltSeriesId, avgrot)
		Database.tiltSeriesDriftMetadata.write(owner.id, tiltSeriesId, dmd)

		virionsAndThresholds?.let { (virions, thresholds) ->
			val list = ParticlesList.autoVirions(owner.id)
			Database.particleLists.createIfNeeded(list)
			Database.particles.importParticles3D(list.ownerId, list.name, tiltSeriesId, virions)
			Database.particles.importVirionThresholds(list.ownerId, list.name, tiltSeriesId, thresholds)
		}
		if (particles != null) {
			val list = ParticlesList.autoParticles3D(owner.id)
			Database.particleLists.createIfNeeded(list)
			Database.particles.importParticles3D(list.ownerId, list.name, tiltSeriesId, particles)
		}

		when (owner) {

			is Owner.Job -> {

				// update the job with the newest micrograph id
				val jobDoc = Database.jobs.get(owner.id)
				if (jobDoc != null) {

					val isFirstTiltSeries = jobDoc["latestTiltSeriesId"] == null
					Database.jobs.update(owner.id,
						set("latestTiltSeriesId", tiltSeriesId)
					)

					// send the update to the clients, if needed
					if (isFirstTiltSeries) {
						// reload the job so it get the tilt series id we just wrote
						val job = Job.fromIdOrThrow(owner.job.idOrThrow)
						val jobData = job.data()
						Backend.projectEventListeners.getAll(job.userId, job.projectId)
							.forEach { it.onJobUpdate(jobData) }
					}
				}

				// notify any listening clients
				TomographyPreprocessingJob.eventListeners.sendTiltSeries(owner.id, tiltSeriesId)
			}

			is Owner.Session -> {

				// notify any listening clients
				TomographySession.events.getListeners(owner.id)
					.forEach { it.onTiltSeries?.invoke(tiltSeriesId) }
			}
		}

		return JsonRpcSuccess()
	}

	suspend fun writeRefinement(params: ObjectNode): JsonRpcResponse {

		// params looks like, eg: {
		//   "webid":"eLtLWR6c1t3ANwBy",
		//   "refinement_id":"20201009_1138_NBtest_A007_G009_H097_D007",
		//   "iteration":5
		// }

		// lookup the job owner
		val clusterJob = ClusterJob.get(params.getStringOrThrow("webid"))
			?: return JsonRpcFailure("invalid webid")
		val owner = clusterJob.findOwner()

		// parse refinement params and update the database
		val refinement = Refinement(
			jobId = owner.id,
			dataId = params.getStringOrThrow("refinement_id"),
			iteration = params.getIntOrThrow("iteration"),
			timestamp = Instant.now()
		)
		refinement.write()

		when (owner) {

			is Owner.Job -> {

				// clean up old iterations, if needed
				val mostRecentIteration = Database.jobs.get(refinement.jobId)
					?.getInteger("latestRefinementIteration")
				if (mostRecentIteration != null && refinement.iteration != mostRecentIteration) {
					Refinement.deleteAllNotIteration(refinement.jobId, refinement.iteration)
				}

				// record the latest iteration, if it's newer
				if (mostRecentIteration == null || refinement.iteration != mostRecentIteration) {
					Database.jobs.update(refinement.jobId,
						set("latestRefinementIteration", refinement.iteration)
					)
				}

				// notify any listening clients
				RefinementJobs.eventListeners.sendRefinement(refinement.jobId, refinement.dataId)
			}

			// don't need this for sessions, right?
			else -> Unit
		}

		return JsonRpcSuccess()
	}

	suspend fun writeRefinementBundle(params: ObjectNode): JsonRpcResponse {

		// params looks like, eg: {
		//   "webid":"eLtLWR6c1t3ANwBy",
		//   "refinement_bundle_id":"1432_2",
		//   "iteration":5
		// }

		// lookup the job owner
		val clusterJob = ClusterJob.get(params.getStringOrThrow("webid"))
			?: return JsonRpcFailure("invalid webid")
		val owner = clusterJob.findOwner()

		// parse params and update the database
		val refinementBundle = RefinementBundle(
			jobId = owner.id,
			refinementBundleId = params.getStringOrThrow("refinement_bundle_id"),
			iteration = params.getIntOrThrow("iteration"),
			timestamp = Instant.now()
		)
		refinementBundle.write()

		when (owner) {

			is Owner.Job -> {

				// clean up old iterations, if needed
				val mostRecentIteration = Database.jobs.get(refinementBundle.jobId)
					?.getInteger("latestRefinementBundleIteration")
				if (mostRecentIteration != null && refinementBundle.iteration != mostRecentIteration) {
					RefinementBundle.deleteAllNotIteration(refinementBundle.jobId, refinementBundle.iteration)
				}

				// record the latest iteration, if it's newer
				if (mostRecentIteration == null || refinementBundle.iteration != mostRecentIteration) {
					Database.jobs.update(refinementBundle.jobId,
						set("latestRefinementBundleIteration", refinementBundle.iteration)
					)
				}

				// notify any listening clients
				RefinementJobs.eventListeners.sendRefinementBundle(owner.id, refinementBundle)
			}

			// don't need this for sessions, right?
			else -> Unit
		}

		return JsonRpcSuccess()
	}

	suspend fun writeClasses(params: ObjectNode): JsonRpcResponse {

		// lookup the job owner
		val clusterJob = ClusterJob.get(params.getStringOrThrow("webid"))
			?: return JsonRpcFailure("invalid webid")
		val owner = clusterJob.findOwner()

		// parse 2d classes params and update the database
		val twoDClasses = TwoDClasses(
			owner.id,
			params.getStringOrThrow("classes_id")
		)
		twoDClasses.write()

		when (owner) {

			is Owner.Job -> {
				// not used in project/job mode, yet?
			}

			is Owner.Session -> {

				// notify any listening clients
				SingleParticleSession.events.getListeners(owner.id)
					.forEach { it.onTwoDClasses?.invoke(twoDClasses) }
			}
		}

		return JsonRpcSuccess()
	}

	sealed class Owner(
		val id: String
	) {
		class Job(val job: edu.duke.bartesaghi.micromon.jobs.Job) : Owner(job.idOrThrow)
		class Session(val session: edu.duke.bartesaghi.micromon.sessions.Session) : Owner(session.idOrThrow)
	}

	private fun ClusterJob.findOwner(): Owner {

		if (ownerId == null) {
			throw NoSuchElementException("cluster job has no owner")
		}

		// try jobs
		JobOwner.fromString(ownerId)?.let { jobOwner ->
			return Owner.Job(Job.fromIdOrThrow(jobOwner.jobId))
		}

		// try sessions
		Session.fromId(ownerId)?.let { session ->
			return Owner.Session(session)
		}

		throw NoSuchElementException("cluster job owner was not recognized: $ownerId")
	}

	suspend fun log(params: ObjectNode): JsonRpcResponse {

		// lookup the job
		val clusterJob = ClusterJob.get(params.getStringOrThrow("webid"))
			?: return JsonRpcFailure("invalid webid")

		// parse the params and update the database
		val msg = StreamLogMsg(
			timestamp = params.getLongOrThrow("timestamp"),
			level = params.getIntOrThrow("level"),
			path = params.getStringOrThrow("path"),
			line = params.getIntOrThrow("line"),
			msg = params.getStringOrThrow("msg")
		)
		StreamLog.add(clusterJob.idOrThrow, msg)

		return JsonRpcSuccess()
	}
}
