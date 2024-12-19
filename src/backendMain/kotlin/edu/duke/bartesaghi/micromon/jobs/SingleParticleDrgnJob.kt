package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.SingleParticleDrgnNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class SingleParticleDrgnJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<SingleParticleDrgnArgs>()

	var inRefinements: CommonJobData.DataId? by InputProp(config.inRefinements)

	companion object : JobInfo {

		override val config = SingleParticleDrgnNodeConfig
		override val dataType = JobInfo.DataType.Micrograph
		override val dataClass = SingleParticleDrgnData::class

		override fun fromDoc(doc: Document) = SingleParticleDrgnJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { SingleParticleDrgnArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { SingleParticleDrgnArgs.fromDoc(it) }
			fromDoc(doc)
		}

		private fun SingleParticleDrgnArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun SingleParticleDrgnArgs.Companion.fromDoc(doc: Document) =
			SingleParticleDrgnArgs(
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
		SingleParticleDrgnData(
			commonData(),
			args,
			diagramImageURL()
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		// build the args for PYP
		val pypArgs = launchArgValues()
		pypArgs.dataMode = "spr"

		Pyp.pyp.launch(project, runId, pypArgs, "Launch", "pyp_launch")

		// job was launched, move the args over
		args.run()
		update()
	}

	fun diagramImageURL(): String {

		val size = ImageSize.Small

		// TEMP: placeholder for now
		return "/img/placeholder/${size.id}"
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
}
