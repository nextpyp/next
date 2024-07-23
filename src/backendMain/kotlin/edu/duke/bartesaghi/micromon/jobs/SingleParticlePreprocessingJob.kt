package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.SingleParticlePreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson
import org.slf4j.LoggerFactory


class SingleParticlePreprocessingJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), FilteredJob {

	val args = JobArgs<SingleParticlePreprocessingArgs>()
	var latestMicrographId: String? = null

	var inMovies: CommonJobData.DataId? by InputProp(config.movies)

	companion object : JobInfo {

		override val config = SingleParticlePreprocessingNodeConfig
		override val dataType = JobInfo.DataType.Micrograph

		override fun fromDoc(doc: Document) = SingleParticlePreprocessingJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { SingleParticlePreprocessingArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { SingleParticlePreprocessingArgs.fromDoc(it) }
			latestMicrographId = doc.getString("latestMicrographId")
			fromDoc(doc)
		}

		private fun SingleParticlePreprocessingArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
			doc["list"] = particlesName
		}

		private fun SingleParticlePreprocessingArgs.Companion.fromDoc(doc: Document) =
			SingleParticlePreprocessingArgs(
				doc.getString("values"),
				doc.getString("list")
			)

		/**
		 * A mechanism to forward micrograph events to websocket clients listening for real-time updates.
		 */
		class EventListeners {

			private val log = LoggerFactory.getLogger("SingleParticlePreprocessing")

			inner class Listener(val jobId: String) : AutoCloseable {

				var onMicrograph: (suspend (Micrograph) -> Unit)? = null
				var onParams: (suspend (ArgValues) -> Unit)? = null

				override fun close() {
					listenersByJob[jobId]?.remove(this)
				}
			}

			private val listenersByJob = HashMap<String,MutableList<Listener>>()

			fun add(jobId: String) =
				Listener(jobId).also {
					listenersByJob.getOrPut(jobId) { ArrayList() }.add(it)
				}

			suspend fun sendMicrograph(jobId: String, micrographId: String) {
				val micrograph = Micrograph.get(jobId, micrographId) ?: return
				listenersByJob[jobId]?.forEach { listener ->
					try {
						listener.onMicrograph?.invoke(micrograph)
					} catch (ex: Throwable) {
						log.error("micrograph listener failed", ex)
					}
				}
			}

			suspend fun sendParams(jobId: String, values: ArgValues) {
				listenersByJob[jobId]?.forEach { listener ->
					try {
						listener.onParams?.invoke(values)
					} catch (ex: Throwable) {
						log.error("params listener failed", ex)
					}
				}
			}
		}
		val eventListeners = EventListeners()
	}

	override fun createDoc(doc: Document) {
		super.createDoc(doc)
		doc["finishedArgs"] = args.finished?.toDoc()
		doc["nextArgs"] = args.next?.toDoc()
		doc["latestMicrographId"] = null
	}

	override fun updateDoc(updates: MutableList<Bson>) {
		super.updateDoc(updates)
		updates.add(Updates.set("finishedArgs", args.finished?.toDoc()))
		updates.add(Updates.set("nextArgs", args.next?.toDoc()))
		updates.add(Updates.set("latestMicrographId", latestMicrographId))
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		SingleParticlePreprocessingData(
			commonData(),
			args,
			diagramImageURL(),
			Database.micrographs.count(idOrThrow),
			Database.particles.countAllParticles(idOrThrow, ParticlesList.PypAutoParticles)
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		val newestArgs = args.newestOrThrow().args

		// if we've picked some particles, write those out to pyp
		newestArgs.particlesName
			?.let { Database.particleLists.get(idOrThrow, it) }
			?.let { ParticlesJobs.writeSingleParticle(project.osUsername, idOrThrow, dir, it) }

		// build the args for PYP
		val upstreamJob = inMovies?.resolveJob<Job>()
			?: throw IllegalStateException("no movies input configured")
		val pypArgs = launchArgValues(upstreamJob, newestArgs.values, args.finished?.values)

		// set the hidden args
		pypArgs.dataMode = "spr"
		pypArgs.dataParent = upstreamJob.dir.toString()

		Pyp.pyp.launch(project.osUsername, runId, pypArgs, "Launch", "pyp_launch")

		// job was launched, move the args over
		args.run()
		// and wipe the latest micrograph id, so we can detect when the first one comes in next time
		latestMicrographId = null
		update()
	}

	fun diagramImageURL(): String {

		val size = ImageSize.Small

		// find an arbitrary (but deterministic) micrograph for this job
		// like the newest micrograph written for this job
		return latestMicrographId
			?.let { "/kv/jobs/$idOrThrow/data/$it/image/${size.id}" }

			// or just use a placeholder
			?: return "/img/placeholder/${size.id}"
	}

	override fun wipeData() {

		// also delete any associated data
		Database.micrographsAvgRot.deleteAll(idOrThrow)
		Database.jobPreprocessingFilters.deleteAll(idOrThrow)
		Database.particleLists.deleteAll(idOrThrow)
		Database.particles.deleteAllParticles(idOrThrow)
	}

	override val filters get() = PreprocessingFilters.ofJob(idOrThrow)

	override fun resolveFilter(filter: PreprocessingFilter): List<String> =
		FilteredJob.resolveFilterMicrographs(idOrThrow, filter)

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
