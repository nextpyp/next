package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographySegmentationClosedNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class TomographySegmentationClosedJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), TiltSeriesesJob {

	val args = JobArgs<TomographySegmentationClosedArgs>()
	override var latestTiltSeriesId: String? = null
	override val eventListeners get() = Companion.eventListeners

	var inParticles: CommonJobData.DataId? by InputProp(config.particles)

	companion object : JobInfo {

		override val config = TomographySegmentationClosedNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries

		override fun fromDoc(doc: Document) = TomographySegmentationClosedJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { TomographySegmentationClosedArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographySegmentationClosedArgs.fromDoc(it) }
			latestTiltSeriesId = doc.getString("latestTiltSeriesId")
			fromDoc(doc)
		}

		private fun TomographySegmentationClosedArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
			doc["filter"] = filter
		}

		private fun TomographySegmentationClosedArgs.Companion.fromDoc(doc: Document) =
			TomographySegmentationClosedArgs(
				doc.getString("values"),
				doc.getString("filter")
			)

		val eventListeners = TiltSeriesEventListeners(this)
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
		TomographySegmentationClosedData(
			commonData(),
			args,
			diagramImageURL(),
			args.finished
				?.particlesList(idOrThrow)
				?.let { Database.instance.particles.countAllParticles(idOrThrow, it.name) }
				?: 0
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()
		val newestArgs = args.newestOrThrow().args

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		val upstreamJob = inParticles?.resolveJob<Job>()
			?: throw IllegalStateException("no particles input configured")

		// write out manually-picked particles from the upstream job, if needed
		ParticlesJobs.clear(project.osUsername, dir)
		upstreamJob.manualParticlesList()
			?.let { ParticlesJobs.writeTomography(project.osUsername, upstreamJob, dir, it) }

		// write out the filter from the upstream job, if needed
		if (upstreamJob is FilteredJob && newestArgs.filter != null) {
			upstreamJob.writeFilter(newestArgs.filter, dir, project.osUsername)
		}

		// build the args for PYP
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
		Database.instance.tiltSeries.deleteAll(idOrThrow)

		// also reset the finished args
		args.unrun()
		latestTiltSeriesId = null
		update()
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
