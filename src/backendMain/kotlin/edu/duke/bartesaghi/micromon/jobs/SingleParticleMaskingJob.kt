package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.SingleParticleMaskingNodeConfig
import edu.duke.bartesaghi.micromon.projects.Project
import edu.duke.bartesaghi.micromon.projects.RepresentativeImage
import edu.duke.bartesaghi.micromon.projects.RepresentativeImageType
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class SingleParticleMaskingJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<SingleParticleMaskingArgs>()
	var inRefinements: CommonJobData.DataId? by InputProp(config.refinements)
	var latestReconstructionId: String?  = null

	companion object : JobInfo {

		override val config = SingleParticleMaskingNodeConfig
		override val dataType = null

		override fun fromDoc(doc: Document) = SingleParticleMaskingJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { SingleParticleMaskingArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { SingleParticleMaskingArgs.fromDoc(it) }
			latestReconstructionId = doc.getString("latestReconstructionId")
			fromDoc(doc)
		}

		private fun SingleParticleMaskingArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun SingleParticleMaskingArgs.Companion.fromDoc(doc: Document) =
			SingleParticleMaskingArgs(
				doc.getString("values")
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
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		SingleParticleMaskingData(
			commonData(),
			args,
			diagramImageURL(),
			getFinishedBfactor()
		)

	override suspend fun launch(runId: Int) {

		// clear caches
		clearWwwCache()

		// get the input jobs
		val prevJob = inRefinements?.resolveJob<Job>() ?: throw IllegalStateException("no refinements input configured")

		// build the args for PYP
		val pypArgs = ArgValues(Backend.pypArgs)

		// set the user args
		pypArgs.setAll(args().diff(
			args.newestOrThrow().args.values,
			args.finished?.values ?: prevJob.finishedArgValues()
		))

		// set the hidden args
		pypArgs.dataParent = prevJob.dir.toString()

		Pyp.pmk.launch(runId, pypArgs, "Launch", "pyp_launch")

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

	fun diagramImageURL(): String =
		representativeImage()?.let { representativeImageUrl(it) }
			?: "img/placeholder/${ImageSize.Small.id}"

	override suspend fun finished() {

		// update the representative image
		Project.representativeImages[userId, projectId, RepresentativeImageType.Map, idOrThrow] = representativeImage()
	}

	override fun wipeData() {

		// remove any reconstructions and refinements
		Database.reconstructions.deleteAll(idOrThrow)
		Database.refinements.deleteAll(idOrThrow)
	}

	fun getFinishedBfactor(): Double? {

		val finished = args.finished ?: return null
		
		return finished
			.values
			.toArgValues(Backend.pypArgs)
			.sharpenCistemHighResBfactor
			?: Backend.pypArgs.defaultSharpenCistemHighResBfactor
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
