package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyMiloEvalNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class TomographyMiloEvalJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<TomographyMiloEvalArgs>()

	var inTomograms: CommonJobData.DataId? by InputProp(config.tomograms)
	var inModel: CommonJobData.DataId? by InputProp(config.model)

	companion object : JobInfo {

		override val config = TomographyMiloEvalNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries

		override fun fromDoc(doc: Document) = TomographyMiloEvalJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { TomographyMiloEvalArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographyMiloEvalArgs.fromDoc(it) }
			fromDoc(doc)
		}

		private fun TomographyMiloEvalArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
			doc["filter"] = filter
		}

		private fun TomographyMiloEvalArgs.Companion.fromDoc(doc: Document) =
			TomographyMiloEvalArgs(
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
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		TomographyMiloEvalData(
			commonData(),
			args,
			diagramImageURL(),
			args.finished
				?.let { Database.instance.particles.countAllParticles(idOrThrow, ParticlesList.AutoParticles) }
				?: 0
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()
		val newestArgs = args.newestOrThrow().args

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		// build the args for PYP
		val upstreamJob = (inModel ?: inTomograms)?.resolveJob<Job>()
			?: throw IllegalStateException("no input configured")

		// write out the filter from the upstream job, if needed
		if (upstreamJob is FilteredJob && newestArgs.filter != null) {
			upstreamJob.writeFilter(newestArgs.filter, dir, project.osUsername)
		}

		val pypArgs = launchArgValues(upstreamJob, newestArgs.values, args.finished?.values)

		// set the hidden args
		pypArgs.dataMode = "tomo"
		pypArgs.dataParent = upstreamJob.dir.toString()

		Pyp.pyp.launch(project.osUsername, runId, pypArgs, "Launch", "pyp_launch")

		// job was launched, move the args over
		args.run()
		update()
	}

	fun diagramImageURL(): String =
		ITomographyMiloEvalService.results2dPath(idOrThrow, ImageSize.Small)

	override fun wipeData() {

		Database.instance.particles.deleteAllParticles(idOrThrow)
		Database.instance.particleLists.deleteAll(idOrThrow)

		// also reset the finished args
		args.unrun()
		update()
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
