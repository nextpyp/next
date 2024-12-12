package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyPickingClosedNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class TomographyPickingClosedJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), TiltSeriesesJob {

	val args = JobArgs<TomographyPickingClosedArgs>()
	override var latestTiltSeriesId: String? = null
	override val eventListeners get() = Companion.eventListeners

	var inSegmentation: CommonJobData.DataId? by InputProp(config.segmentation)

	companion object : JobInfo {

		override val config = TomographyPickingClosedNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries
		override val dataClass = TomographyPickingClosedData::class

		override fun fromDoc(doc: Document) = TomographyPickingClosedJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { TomographyPickingClosedArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographyPickingClosedArgs.fromDoc(it) }
			latestTiltSeriesId = doc.getString("latestTiltSeriesId")
			fromDoc(doc)
		}

		private fun TomographyPickingClosedArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun TomographyPickingClosedArgs.Companion.fromDoc(doc: Document) =
			TomographyPickingClosedArgs(
				doc.getString("values")
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
		TomographyPickingClosedData(
			commonData(),
			args,
			diagramImageURL(),
			args.finished
				?.particlesList(args(), idOrThrow)
				?.let { Database.instance.particles.countAllParticles(idOrThrow, it.name) }
				?: 0
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		val upstreamJob = inSegmentation?.resolveJob<Job>()
			?: throw IllegalStateException("no segmentation input configured")

		// always write out particles from the upstream job, so we can get the segmentation thresholds
		ParticlesJobs.clear(project.osUsername, dir)
		ParticlesJobs.writeTomography(project.osUsername, upstreamJob, dir, ParticlesList.autoVirions(upstreamJob.idOrThrow))

		// build the args for PYP
		val pypArgs = launchArgValues()
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
		Database.instance.tiltSeries.deleteAll(idOrThrow)
		Database.instance.particleLists.deleteAll(idOrThrow)
		Database.instance.particles.deleteAllParticles(idOrThrow)

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
