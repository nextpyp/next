package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.Tomography3DAVGNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class Tomography3DAVGJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<Tomography3DAVGArgs>()

	var movieRefinement: CommonJobData.DataId? by InputProp(config.movieRefinement)

	companion object : JobInfo {

		override val config = Tomography3DAVGNodeConfig
		override val dataType = JobInfo.DataType.Micrograph
		override val dataClass = Tomography3DAVGData::class

		override fun fromDoc(doc: Document) = Tomography3DAVGJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { Tomography3DAVGArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { Tomography3DAVGArgs.fromDoc(it) }
			fromDoc(doc)
		}

		private fun Tomography3DAVGArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun Tomography3DAVGArgs.Companion.fromDoc(doc: Document) =
			Tomography3DAVGArgs(
				doc.getString("values")
			)
	}

	override fun createDoc(doc: Document) {
		super.createDoc(doc)
		doc["finishedArgs"] = args.finished?.toDoc()
		doc["nextArgs"] = args.next?.toDoc()
		doc["latestMicrographId"] = null
	}

	override fun updateDoc(updates: MutableList<Bson>) {
		super.updateDoc(updates)
		updates.add(Updates.set("finishedArgs", args.finished?.toDoc()))
		updates.add(Updates.set("nextArgs", args.next?.toDoc()))
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		Tomography3DAVGData(
			commonData(),
			args,
			diagramImageURL()
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreate()

		// build the args for PYP
		val pypArgs = launchArgValues()
		pypArgs.dataMode = "tomo"

		Pyp.sva.launch(project, runId, pypArgs, "Launch", "pyp_launch")

		// job was launched, move the args over
		args.run()
		update()
	}


	fun diagramImageURL(): String {

		val size = ImageSize.Small
		val iter = 0

		return ITomography3DAVGService.representativeClassImagePath(idOrThrow, iter, size)
	}

	override fun wipeData() {

		// also delete any associated data
		// TODO: what metadata is there for this job?

		// also reset the finished args
		args.unrun()
		update()
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
	
	fun params(): Tomography3DAVGParams? =
		pypParameters()?.let {
			Tomography3DAVGParams(
				classes = it.svaClassNum,
				iterations = it.svaIterations,
				centeringIterations = it.svaCenteringIterations,
				applySymmetry = it.svaApplySymmetry
			)
		}
}
