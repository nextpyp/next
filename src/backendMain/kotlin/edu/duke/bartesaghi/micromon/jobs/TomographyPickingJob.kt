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
) : Job(userId, projectId, config), TiltSeriesesJob, ManualParticlesJob {

	val args = JobArgs<TomographyPickingArgs>()
	override var latestTiltSeriesId: String? = null
	override val eventListeners get() = Companion.eventListeners

	var inTomograms: CommonJobData.DataId? by InputProp(config.tomograms)

	companion object : JobInfo {

		override val config = TomographyPickingNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries
		override val dataClass = TomographyPickingData::class

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
			doc["filter"] = filter
			doc["particlesName"] = particlesName
		}

		private fun TomographyPickingArgs.Companion.fromDoc(doc: Document) =
			TomographyPickingArgs(
				doc.getString("values"),
				doc.getString("filter"),
				doc.getString("particlesName")
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
		TomographyPickingData(
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
		val newestArgs = args.newestOrThrow().args

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		// get the input jobs
		val upstreamJob = inTomograms?.resolveJob<Job>()
			?: throw IllegalStateException("no tomograms input configured")

		// write out the filter from the upstream job, if needed
		if (upstreamJob is FilteredJob && newestArgs.filter != null) {
			upstreamJob.writeFilter(newestArgs.filter, dir, project.osUsername)
		}

		// write out manually-picked particles from the upstream job, if needed
		ParticlesJobs.clear(project.osUsername, dir)
		manualParticlesList(newestArgs.particlesName)
			?.let { ParticlesJobs.writeTomography(project.osUsername, this, dir, it) }

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
		Database.instance.parameters.delete(idOrThrow)

		// also reset the finished args
		args.unrun()
		latestTiltSeriesId = null
		update()
	}

	override fun copyDataFrom(otherJobId: String) {

		Database.instance.tiltSeries.copyAll(otherJobId, idOrThrow)
		Database.instance.particleLists.copyAll(otherJobId, idOrThrow)
		Database.instance.particles.copyAllParticles(otherJobId, idOrThrow)
		Database.instance.parameters.copy(otherJobId, idOrThrow)

		latestTiltSeriesId = fromIdOrThrow(otherJobId).cast<TomographyPickingJob>().latestTiltSeriesId
		update()
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values

	override fun manualParticlesListName(): String =
		ParticlesList.ManualParticles
}
