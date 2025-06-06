package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyCoarseRefinementNodeConfig
import edu.duke.bartesaghi.micromon.projects.Project
import edu.duke.bartesaghi.micromon.projects.RepresentativeImage
import edu.duke.bartesaghi.micromon.projects.RepresentativeImageType
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class TomographyCoarseRefinementJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<TomographyCoarseRefinementArgs>()
	var inMovieRefinement: CommonJobData.DataId? by InputProp(config.movieRefinement)
	var latestReconstructionId: String?  = null
	var jobInfoString: String? = null

	companion object : JobInfo {

		override val config = TomographyCoarseRefinementNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries
		override val dataClass = TomographyCoarseRefinementData::class

		override fun fromDoc(doc: Document) = TomographyCoarseRefinementJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { TomographyCoarseRefinementArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographyCoarseRefinementArgs.fromDoc(it) }
			latestReconstructionId = doc.getString("latestReconstructionId")
			jobInfoString = doc.getString("jobInfoString")
			fromDoc(doc)
		}

		private fun TomographyCoarseRefinementArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
			doc["filter"] = filter
		}

		private fun TomographyCoarseRefinementArgs.Companion.fromDoc(doc: Document) =
			TomographyCoarseRefinementArgs(
				doc.getString("values"),
				doc.getString("filter")
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
		updates.add(Updates.set("latestReconstructionId", latestReconstructionId))
		updates.add(Updates.set("jobInfoString", jobInfoString))
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		TomographyCoarseRefinementData(
			commonData(),
			args,
			diagramImageURL(),
			jobInfoString ?: ""
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()
		val newestArgs = args.newestOrThrow().args

		// clear caches
		wwwDir.recreate()

		// get the input jobs
		val upstreamJob = inMovieRefinement?.resolveJob<Job>()
			?: throw IllegalStateException("no movie refinement input configured")

		// write out the filter from the upstream job, if needed
		if (upstreamJob is FilteredJob && newestArgs.filter != null) {
			upstreamJob.writeFilter(newestArgs.filter, dir, project.osUsername)
		}

		// write out manual particles from the upstream job, so we can use them for training
		ParticlesJobs.clear(project.osUsername, dir)
		upstreamJob.manualParticlesList()
			?.let { ParticlesJobs.writeTomography(project.osUsername, upstreamJob, dir, it) }

		// build the args for PYP
		val pypArgs = launchArgValues()
		pypArgs.extractFmt = "frealign"

		Pyp.csp.launch(project, runId, pypArgs, "Launch", "pyp_launch")

		// job was launched, move the args over
		args.run()
		update()
	}

	private fun latestReconstruction(): Reconstruction? =
		latestReconstructionId
			?.let { Reconstruction.get(idOrThrow, it) }

	override fun representativeImage() =
		latestReconstruction()?.representativeImage()

	override fun representativeImageUrl(repImage: RepresentativeImage) =
		Reconstruction.representativeImageUrl(idOrThrow, repImage)

	override suspend fun finished() {

		// update the representative image
		Project.representativeImages[userId, projectId, RepresentativeImageType.Map, idOrThrow] = representativeImage()
	}

	override fun wipeData() {

		// remove any reconstructions and refinements
		Database.instance.reconstructions.deleteAll(idOrThrow)
		Database.instance.refinements.deleteAll(idOrThrow)

		// also reset the finished args
		args.unrun()
		latestReconstructionId = null
		update()
	}

	fun diagramImageURL(): String =
		representativeImage()?.let { representativeImageUrl(it) }
			?: "img/placeholder/${ImageSize.Small.id}"

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
