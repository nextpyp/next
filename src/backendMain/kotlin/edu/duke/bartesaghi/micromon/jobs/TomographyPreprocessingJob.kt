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
import io.kvision.remote.ServiceException
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.io.path.div


class TomographyPreprocessingJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), FilteredJob, TiltSeriesesJob, CombinedManualParticlesJob {

	val args = JobArgs<TomographyPreprocessingArgs>()

	override var latestTiltSeriesId: String? = null
	override val eventListeners get() = Companion.eventListeners

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

		val eventListeners = TiltSeriesEventListeners(this)
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
			Database.particles.countAllParticles(idOrThrow, ParticlesList.AutoParticles)
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		val newestArgs = args.newestOrThrow().args

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

		// check if we need to pick a manual particles list or not
		if (newestArgs.tomolist == null) {
			val newestValues = newestArgs.values.toArgValues(Backend.pypArgs)
			if (newestValues.tomoSpkMethodOrDefault.particlesList(idOrThrow)?.source == ParticlesSource.User) {
				throw ServiceException("Manual particles list is required for particle picking method")
			} else if (newestValues.tomoVirMethodOrDefault.particlesList(idOrThrow)?.source == ParticlesSource.User) {
				throw ServiceException("Manual particles list is required for virion picking method")
			} else if (newestValues.tomoVirDetectMethodOrDefault.particlesList(idOrThrow)?.source == ParticlesSource.User) {
				throw ServiceException("Manual particles list is required for spike picking method")
			}
		}

		// write out manually-picked particles, if needed
		ParticlesJobs.clear(project.osUsername, dir)
		manualParticlesList(newestArgs.tomolist)
			?.let { ParticlesJobs.writeTomography(project.osUsername, this, dir, it) }

		// build the args for PYP
		val upstreamJob = inTiltSeries?.resolveJob<Job>()
			?: throw IllegalStateException("no tilt series input configured")
		val pypArgs = launchArgValues(upstreamJob, newestArgs.values, args.finished?.values)

		// set the hidden args
		pypArgs.dataMode = "tomo"
		pypArgs.dataParent = upstreamJob.dir.toString()

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
		Database.tiltSeries.deleteAll(idOrThrow)
		Database.tiltSeriesAvgRot.deleteAll(idOrThrow)
		Database.tiltSeriesDriftMetadata.deleteAll(idOrThrow)
		Database.jobPreprocessingFilters.deleteAll(idOrThrow)
		Database.particleLists.deleteAll(idOrThrow)
		Database.particles.deleteAllParticles(idOrThrow)
		Database.tiltExclusions.delete(idOrThrow)

		// also reset the finished args
		args.unrun()
		latestTiltSeriesId = null
		update()
	}

	override val filters get() = PreprocessingFilters.ofJob(idOrThrow)

	override fun resolveFilter(filter: PreprocessingFilter): List<String> =
		FilteredJob.resolveFilterTiltSeries(idOrThrow, filter)

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
