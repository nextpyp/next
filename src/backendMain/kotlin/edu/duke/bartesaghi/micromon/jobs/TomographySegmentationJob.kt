package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographySegmentationNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class TomographySegmentationJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<TomographySegmentationArgs>()

	var inTomograms: CommonJobData.DataId? by InputProp(config.tomograms)

	companion object : JobInfo {

		override val config = TomographySegmentationNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries

		override fun fromDoc(doc: Document) = TomographySegmentationJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { TomographySegmentationArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographySegmentationArgs.fromDoc(it) }
			fromDoc(doc)
		}

		private fun TomographySegmentationArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun TomographySegmentationArgs.Companion.fromDoc(doc: Document) =
			TomographySegmentationArgs(
				doc.getString("values")
			)
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
		TomographySegmentationData(
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
			?: throw IllegalStateException("no tilt series input configured")
		val pypArgs = launchArgValues(upstreamJob, args.newestOrThrow().args.values, args.finished?.values)

		// set the hidden args
		pypArgs.dataMode = "tomo"

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

		// also delete any associated data
		// TODO
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
