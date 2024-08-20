package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyParticlesEvalNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class TomographyParticlesEvalJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), TiltSeriesesJob {

	val args = JobArgs<TomographyParticlesEvalArgs>()
	override var latestTiltSeriesId: String? = null
	override val eventListeners get() = TomographyPickingJob.eventListeners

	var inModel: CommonJobData.DataId? by InputProp(config.model)

	companion object : JobInfo {

		override val config = TomographyParticlesEvalNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries

		override fun fromDoc(doc: Document) = TomographyParticlesEvalJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { TomographyParticlesEvalArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographyParticlesEvalArgs.fromDoc(it) }
			latestTiltSeriesId = doc.getString("latestTiltSeriesId")
			fromDoc(doc)
		}

		private fun TomographyParticlesEvalArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun TomographyParticlesEvalArgs.Companion.fromDoc(doc: Document) =
			TomographyParticlesEvalArgs(
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
		TomographyParticlesEvalData(
			commonData(),
			args,
			diagramImageURL(),
			args.finished
				?.particlesList(idOrThrow)
				?.let { Database.particles.countAllParticles(idOrThrow, it.name) }
				?: 0
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		// build the args for PYP
		val upstreamJob = inModel?.resolveJob<Job>()
			?: throw IllegalStateException("no model input configured")
		val pypArgs = launchArgValues(upstreamJob, args.newestOrThrow().args.values, args.finished?.values)

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
		Database.particleLists.deleteAll(idOrThrow)
		Database.particles.deleteAllParticles(idOrThrow)
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
