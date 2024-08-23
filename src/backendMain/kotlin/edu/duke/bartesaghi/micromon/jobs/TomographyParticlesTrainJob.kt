package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyParticlesTrainNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.remote.ServiceException
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
		}

		private fun TomographyParticlesTrainArgs.Companion.fromDoc(doc: Document) =
			TomographyParticlesTrainArgs(
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
		val newestArgs = args.newestOrThrow().args

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		val upstreamJob = inParticles?.resolveJob<Job>()
			?: throw IllegalStateException("no particles input configured")

		// write out particles from the upstream job, if needed
		ParticlesJobs.clear(project.osUsername, dir)
		when (upstreamJob) {
			is CombinedParticlesJob -> throw ServiceException("Particle training is not implemented from legacy preprocessing blocks")
			is ParticlesJob -> {
				upstreamJob.particlesList()
					?.let { ParticlesJobs.writeTomography(project.osUsername, upstreamJob.idOrThrow, dir, it) }
			}
			else -> throw IllegalStateException("upstream job has no particles")
		}

		// build the args for PYP
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
		IJobsService.outputImage(idOrThrow, ImageSize.Small)

	override fun wipeData() {

		// TODO: also delete any associated data?
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
