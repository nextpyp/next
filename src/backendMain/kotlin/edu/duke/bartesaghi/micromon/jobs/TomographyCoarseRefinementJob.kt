package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.createDirsIfNeeded
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyCoarseRefinementNodeConfig
import edu.duke.bartesaghi.micromon.projects.Project
import edu.duke.bartesaghi.micromon.projects.RepresentativeImage
import edu.duke.bartesaghi.micromon.projects.RepresentativeImageType
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.writeString
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.io.path.div


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
		override val dataType = null

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

		fun args() =
			Backend.pypArgs
				.filter(config.configId, includeHiddenArgs = false, includeHiddenGroups = true)
				.appendAll(MicromonArgs.slurmLaunch)
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

		// clear caches
		clearWwwCache()

		// get the input jobs
		val preprocessingJob = inMovieRefinement?.resolveJob<Job>() ?: throw IllegalStateException("no movie refinement input configured")

		// are we using a tilt series filter?
		if (preprocessingJob is FilteredJob) {
			val filter = args.newestOrThrow().args.filter
				?.let { filter -> preprocessingJob.filters[filter] }
			if (filter != null) {

				// write out the micrographs file to the job folder before starting pyp
				val dir = dir.createDirsIfNeeded()
				val file = dir / "${dir.fileName}.micrographs"
				val tiltSeriesIds = preprocessingJob.resolveFilter(filter)
				file.writeString(tiltSeriesIds.joinToString("\n"))
			}
		}

		// build the args for PYP
		val pypArgs = ArgValues(Backend.pypArgs)

		// set the user args
		pypArgs.setAll(args().diff(
			args.newestOrThrow().args.values,
			args.finished?.values ?: preprocessingJob.finishedArgValues()
		))

		// set the hidden args
		pypArgs.dataParent = preprocessingJob.dir.toString()
		pypArgs.extractFmt = "frealign"

		Pyp.csp.launch(runId, pypArgs, "Launch", "pyp_launch")

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
		Database.reconstructions.deleteAll(idOrThrow)
		Database.refinements.deleteAll(idOrThrow)
	}

	fun diagramImageURL(): String =
		representativeImage()?.let { representativeImageUrl(it) }
			?: "img/placeholder/${ImageSize.Small.id}"

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
