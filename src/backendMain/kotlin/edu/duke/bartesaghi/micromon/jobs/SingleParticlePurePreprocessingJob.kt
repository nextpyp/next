package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.SingleParticlePurePreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class SingleParticlePurePreprocessingJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), FilteredJob, MicrographsJob {

	val args = JobArgs<SingleParticlePurePreprocessingArgs>()
	override var latestMicrographId: String? = null
	override val eventListeners get() = Companion.eventListeners

	var inMovies: CommonJobData.DataId? by InputProp(config.movies)

	companion object : JobInfo {

		override val config = SingleParticlePurePreprocessingNodeConfig
		override val dataType = JobInfo.DataType.Micrograph

		override fun fromDoc(doc: Document) = SingleParticlePurePreprocessingJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { SingleParticlePurePreprocessingArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { SingleParticlePurePreprocessingArgs.fromDoc(it) }
			latestMicrographId = doc.getString("latestMicrographId")
			fromDoc(doc)
		}

		private fun SingleParticlePurePreprocessingArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun SingleParticlePurePreprocessingArgs.Companion.fromDoc(doc: Document) =
			SingleParticlePurePreprocessingArgs(
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
		SingleParticlePurePreprocessingData(
			commonData(),
			args,
			diagramImageURL(),
			Database.micrographs.count(idOrThrow)
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		val newestArgs = args.newestOrThrow().args

		// build the args for PYP
		val upstreamJob = inMovies?.resolveJob<Job>()
			?: throw IllegalStateException("no movies input configured")
		val pypArgs = launchArgValues(upstreamJob, newestArgs.values, args.finished?.values)

		// set the hidden args
		pypArgs.dataMode = "spr"
		// NOTE: even though this is not a source block, setting the data parent causes pyp to throw errors, so don't do it here

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
		Database.micrographs.deleteAll(idOrThrow)
		Database.micrographsAvgRot.deleteAll(idOrThrow)
		Database.jobPreprocessingFilters.deleteAll(idOrThrow)
	}

	override val filters get() = PreprocessingFilters.ofJob(idOrThrow)

	override fun resolveFilter(filter: PreprocessingFilter): List<String> =
		FilteredJob.resolveFilterMicrographs(idOrThrow, filter)

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
