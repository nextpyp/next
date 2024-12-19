package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyParticlesTrainNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class TomographyParticlesTrainJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<TomographyParticlesTrainArgs>()

	var inParticles: CommonJobData.DataId? by InputProp(config.particles)

	companion object : JobInfo {

		override val config = TomographyParticlesTrainNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries
		override val dataClass = TomographyParticlesTrainData::class

		override fun fromDoc(doc: Document) = TomographyParticlesTrainJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { TomographyParticlesTrainArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographyParticlesTrainArgs.fromDoc(it) }
			fromDoc(doc)
		}

		private fun TomographyParticlesTrainArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
			doc["particlesName"] = particlesName
		}

		private fun TomographyParticlesTrainArgs.Companion.fromDoc(doc: Document) =
			TomographyParticlesTrainArgs(
				doc.getString("values"),
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
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		TomographyParticlesTrainData(
			commonData(),
			args,
			diagramImageURL()
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		val upstreamJob = inParticles?.resolveJob<Job>()
			?: throw IllegalStateException("no particles input configured")

		// write out manual particles from the upstream job, so we can use them for training
		ParticlesJobs.clear(project.osUsername, dir)
		upstreamJob.manualParticlesList()
			?.let { ParticlesJobs.writeTomography(project.osUsername, upstreamJob, dir, it) }

		// build the args for PYP
		val pypArgs = launchArgValues()
		pypArgs.dataMode = "tomo"

		Pyp.pyp.launch(project, runId, pypArgs, "Launch", "pyp_launch")

		// job was launched, move the args over
		args.run()
		update()
	}

	fun diagramImageURL(): String =
		ITomographyParticlesTrainService.resultsPath(idOrThrow)

	override fun wipeData() {

		// TODO: also delete any associated data?

		// also reset the finished args
		args.unrun()
		update()
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
