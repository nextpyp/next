package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.createDirsIfNeededAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.deleteDirRecursivelyAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.writerAs
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyPreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson
import org.slf4j.LoggerFactory
import kotlin.io.path.div


class TomographyPreprocessingJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), FilteredJob {

	val args = JobArgs<TomographyPreprocessingArgs>()
	var latestTiltSeriesId: String? = null

	var inTiltSeries: CommonJobData.DataId? by InputProp(config.tiltSeries)

	companion object : JobInfo {

		override val config = TomographyPreprocessingNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries

		override fun fromDoc(doc: Document) = TomographyPreprocessingJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { TomographyPreprocessingArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographyPreprocessingArgs.fromDoc(it) }
			latestTiltSeriesId = doc.getString("latestTiltSeriesId")
			fromDoc(doc)
		}

		private fun TomographyPreprocessingArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
			doc["tomolist"] = tomolist
		}

		private fun TomographyPreprocessingArgs.Companion.fromDoc(doc: Document) =
			TomographyPreprocessingArgs(
				doc.getString("values"),
				doc.getString("tomolist")
			)

		/**
		 * A mechanism to forward tilt series events to websocket clients listening for real-time updates.
		 */
		class EventListeners {

			private val log = LoggerFactory.getLogger("TomographyPreprocessing")

			inner class Listener(val jobId: String) : AutoCloseable {

				var onTiltSeries: (suspend (TiltSeries) -> Unit)? = null
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

			suspend fun sendTiltSeries(jobId: String, tiltSeriesId: String) {
				val tiltSeries = TiltSeries.get(jobId, tiltSeriesId) ?: return
				listenersByJob[jobId]?.forEach { listener ->
					try {
						listener.onTiltSeries?.invoke(tiltSeries)
					} catch (ex: Throwable) {
						log.error("tilt series listener failed", ex)
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
		doc["latestTiltSeriesId"] = null
	}

	override fun updateDoc(updates: MutableList<Bson>) {
		super.updateDoc(updates)
		updates.add(Updates.set("finishedArgs", args.finished?.toDoc()))
		updates.add(Updates.set("nextArgs", args.next?.toDoc()))
		updates.add(Updates.set("latestTiltSeriesId", latestTiltSeriesId))
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		TomographyPreprocessingData(
			commonData(),
			args,
			diagramImageURL(),
			Database.tiltSeries.count(idOrThrow),
			Database.particles.countAllParticles(idOrThrow, ParticlesList.PypAutoParticles)
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		// get the input raw data job
		val prevJob = inTiltSeries?.resolveJob<Job>() ?: throw IllegalStateException("no tilt series input configured")

		val newestArgs = args.newestOrThrow().args

		// write out particles, if needed
		val argValues = newestArgs.values.toArgValues(Backend.pypArgs)
		ParticlesJobs.writeTomography(project.osUsername, idOrThrow, dir, argValues, newestArgs.tomolist)

		// write out the tilt exclusions, if needed
		run {

			val dir = dir / "next"
			val suffix = "_exclude_views.next"

			// delete any old files
			dir.deleteDirRecursivelyAs(project.osUsername)
			dir.createDirsIfNeededAs(project.osUsername)

			// write any new files
			val exclusionsByTiltSeries = Database.tiltExclusions.getForJob(idOrThrow)
			if (exclusionsByTiltSeries != null) {
				for ((tiltSeriesId, exclusionsByTiltIndex) in exclusionsByTiltSeries) {
					val file = dir / "$tiltSeriesId$suffix"
					file.writerAs(project.osUsername).use { writer ->
						for ((tiltIndex, isExcluded) in exclusionsByTiltIndex) {
							if (isExcluded) {
								writer.write("0\t0\t$tiltIndex\n")
							}
						}
					}
				}
			}
		}

		// build the args for PYP
		val pypArgs = ArgValues(Backend.pypArgs)

		// set the user args
		pypArgs.setAll(args().diff(
			newestArgs.values,
			args.finished?.values ?: prevJob.finishedArgValues()
		))

		// set the hidden args
		pypArgs.dataMode = "tomo"

		Pyp.pyp.launch(project.osUsername, runId, pypArgs, "Launch", "pyp_launch")

		// job was launched, move the args over
		args.run()
		// and wipe the latest tilt series id, so we can detect when the first one comes in next time
		latestTiltSeriesId = null
		update()
	}

	fun diagramImageURL(): String {

		val size = ImageSize.Small

		// find an arbitrary (but deterministic) tilt series for this job
		// like the newest tilt series written for this job
		return latestTiltSeriesId
			?.let { "/kv/jobs/$idOrThrow/data/$it/image/${size.id}" }

			// or just use a placeholder
			?: return "/img/placeholder/${size.id}"
	}

	override fun wipeData() {

		// also delete any associated data
		Database.tiltSeriesAvgRot.deleteAll(idOrThrow)
		Database.tiltSeriesDriftMetadata.deleteAll(idOrThrow)
		Database.jobPreprocessingFilters.deleteAll(idOrThrow)
		Database.particleLists.deleteAll(idOrThrow)
		Database.particles.deleteAllParticles(idOrThrow)
		Database.tiltExclusions.delete(idOrThrow)
	}

	override val filters get() = PreprocessingFilters.ofJob(idOrThrow)

	override fun resolveFilter(filter: PreprocessingFilter): List<String> =
		FilteredJob.resolveFilterTiltSeries(idOrThrow, filter)

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
