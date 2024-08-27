package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographySegmentationClosedNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.remote.ServiceException
import org.bson.Document
import org.bson.conversions.Bson


class TomographySegmentationClosedJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<TomographySegmentationClosedArgs>()

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
			fromDoc(doc)
		}

		private fun TomographySegmentationClosedArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun TomographySegmentationClosedArgs.Companion.fromDoc(doc: Document) =
			TomographySegmentationClosedArgs(
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
		TomographySegmentationClosedData(
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
			is CombinedParticlesJob -> throw ServiceException("Closed segmentation is not implemented from legacy preprocessing blocks")
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

	fun diagramImageURL(): String {

		val size = ImageSize.Small

		// TEMP: use a placeholder for now
		return "/img/placeholder/${size.id}"
	}

	override fun wipeData() {

		// TODO: also delete any associated data
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
