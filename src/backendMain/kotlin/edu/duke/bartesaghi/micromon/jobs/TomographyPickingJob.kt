package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyPickingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class TomographyPickingJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), TiltSeriesesJob {

	val args = JobArgs<TomographyPickingArgs>()
	override var latestTiltSeriesId: String? = null

	var inTomograms: CommonJobData.DataId? by InputProp(config.tomograms)
	var inSegmentation: CommonJobData.DataId? by InputProp(config.segmentation)

	companion object : JobInfo {

		override val config = TomographyPickingNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries

		override fun fromDoc(doc: Document) = TomographyPickingJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { TomographyPickingArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographyPickingArgs.fromDoc(it) }
			latestTiltSeriesId = doc.getString("latestTiltSeriesId")
			fromDoc(doc)
		}

		private fun TomographyPickingArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun TomographyPickingArgs.Companion.fromDoc(doc: Document) =
			TomographyPickingArgs(
				doc.getString("values")
			)

		val eventListeners = TomographyPreprocessingJob.Companion.EventListeners()
	}

	override fun createDoc(doc: Document) {
		super.createDoc(doc)
		doc["finishedArgs"] = args.finished?.toDoc()
		doc["nextArgs"] = args.next?.toDoc()
	}

	override fun updateDoc(updates: MutableList<Bson>) {
		super.updateDoc(updates)
		updates.add(Updates.set("finishedArgs", args.finished?.toDoc()))
		updates.add(Updates.set("nextArgs", args.next?.toDoc()))
		updates.add(Updates.set("latestTiltSeriesId", latestTiltSeriesId))
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		TomographyPickingData(
			commonData(),
			args,
			diagramImageURL()
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		// build the args for PYP
		val upstreamJob = (inSegmentation ?: inTomograms)?.resolveJob<Job>()
			?: throw IllegalStateException("no tomograms or segmentation input configured")
		val pypArgs = launchArgValues(upstreamJob, args.newestOrThrow().args.values, args.finished?.values)

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
		Database.tiltExclusions.delete(idOrThrow)
		Database.particles.deleteAllParticles(idOrThrow)
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values

	override suspend fun notifyTiltSeries(tiltSeriesId: String) {
		eventListeners.sendTiltSeries(idOrThrow, tiltSeriesId)
	}
}
