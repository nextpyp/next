package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.SingleParticlePickingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class SingleParticlePickingJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), MicrographsJob {

	val args = JobArgs<SingleParticlePickingArgs>()
	override var latestMicrographId: String? = null
	override val eventListeners get() = Companion.eventListeners

	var inMicrographs: CommonJobData.DataId? by InputProp(config.micrographs)

	companion object : JobInfo {

		override val config = SingleParticlePickingNodeConfig
		override val dataType = JobInfo.DataType.Micrograph
		override val dataClass = SingleParticlePickingData::class

		override fun fromDoc(doc: Document) = SingleParticlePickingJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { SingleParticlePickingArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { SingleParticlePickingArgs.fromDoc(it) }
			latestMicrographId = doc.getString("latestMicrographId")
			fromDoc(doc)
		}

		private fun SingleParticlePickingArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun SingleParticlePickingArgs.Companion.fromDoc(doc: Document) =
			SingleParticlePickingArgs(
				doc.getString("values")
			)

		val eventListeners = MicrographEventListeners(this)
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
		updates.add(Updates.set("latestMicrographId", latestMicrographId))
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		SingleParticlePickingData(
			commonData(),
			args,
			diagramImageURL()
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		val newestArgs = args.newestOrThrow().args

		// build the args for PYP
		val upstreamJob = inMicrographs?.resolveJob<Job>()
			?: throw IllegalStateException("no micrographs input configured")
		val pypArgs = launchArgValues(upstreamJob, newestArgs.values, args.finished?.values)

		// set the hidden args
		pypArgs.dataMode = "spr"
		pypArgs.dataParent = upstreamJob.dir.toString()

		Pyp.pyp.launch(project.osUsername, runId, pypArgs, "Launch", "pyp_launch")

		// job was launched, move the args over
		args.run()
		// and wipe the latest micrograph id, so we can detect when the first one comes in next time
		latestMicrographId = null
		update()
	}

	fun diagramImageURL(): String {

		val size = ImageSize.Small

		// find an arbitrary (but deterministic) micrograph for this job
		// like the newest micrograph written for this job
		return latestMicrographId
			?.let { "/kv/jobs/$idOrThrow/data/$it/image/${size.id}" }

			// or just use a placeholder
			?: return "/img/placeholder/${size.id}"
	}

	override fun wipeData() {

		// also delete any associated data
		Database.instance.micrographs.deleteAll(idOrThrow)
		Database.instance.particleLists.deleteAll(idOrThrow)
		Database.instance.particles.deleteAllParticles(idOrThrow)

		// also reset the finished args
		args.unrun()
		latestMicrographId = null
		update()
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
