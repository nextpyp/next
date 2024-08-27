package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographySegmentationOpenNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class TomographySegmentationOpenJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<TomographySegmentationOpenArgs>()

	var inTomograms: CommonJobData.DataId? by InputProp(config.tomograms)

	companion object : JobInfo {

		override val config = TomographySegmentationOpenNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries

		override fun fromDoc(doc: Document) = TomographySegmentationOpenJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { TomographySegmentationOpenArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographySegmentationOpenArgs.fromDoc(it) }
			fromDoc(doc)
		}

		private fun TomographySegmentationOpenArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun TomographySegmentationOpenArgs.Companion.fromDoc(doc: Document) =
			TomographySegmentationOpenArgs(
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
		TomographySegmentationOpenData(
			commonData(),
			args,
			diagramImageURL()
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		// build the args for PYP
		val upstreamJob = inTomograms?.resolveJob<Job>()
			?: throw IllegalStateException("no tomograms input configured")
		val pypArgs = launchArgValues(upstreamJob, args.newestOrThrow().args.values, args.finished?.values)

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
