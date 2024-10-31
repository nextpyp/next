package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.SingleParticlePostprocessingNodeConfig
import edu.duke.bartesaghi.micromon.projects.Project
import edu.duke.bartesaghi.micromon.projects.RepresentativeImage
import edu.duke.bartesaghi.micromon.projects.RepresentativeImageType
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class SingleParticlePostprocessingJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<SingleParticlePostprocessingArgs>()
	var inRefinements: CommonJobData.DataId? by InputProp(config.refinements)
	var latestReconstructionId: String?  = null

	companion object : JobInfo {

		override val config = SingleParticlePostprocessingNodeConfig
		override val dataType = JobInfo.DataType.Micrograph

		override fun fromDoc(doc: Document) = SingleParticlePostprocessingJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { SingleParticlePostprocessingArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { SingleParticlePostprocessingArgs.fromDoc(it) }
			latestReconstructionId = doc.getString("latestReconstructionId")
			fromDoc(doc)
		}

		private fun SingleParticlePostprocessingArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun SingleParticlePostprocessingArgs.Companion.fromDoc(doc: Document) =
			SingleParticlePostprocessingArgs(
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
		updates.add(Updates.set("latestReconstructionId", latestReconstructionId))
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		SingleParticlePostprocessingData(
			commonData(),
			args,
			diagramImageURL(),
			getFinishedBfactor()
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		// build the args for PYP
		val upstreamJob = inRefinements?.resolveJob<Job>()
			?: throw IllegalStateException("no refinements input configured")
		val pypArgs = launchArgValues(upstreamJob, args.newestOrThrow().args.values, args.finished?.values)

		// set the hidden args
		pypArgs.dataParent = upstreamJob.dir.toString()

		Pyp.psp.launch(project.osUsername, runId, pypArgs, "Launch", "pyp_launch")

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
		Database.instance.reconstructions.deleteAll(idOrThrow)
		Database.instance.refinements.deleteAll(idOrThrow)

		// also reset the finished args
		args.unrun()
		latestReconstructionId = null
		update()
	}

	fun getFinishedBfactor(): Double? {

		val finished = args.finished ?: return null
		
		return finished
			.values
			.toArgValues(Backend.instance.pypArgs)
			.sharpenCistemHighResBfactor
			?: Backend.instance.pypArgs.defaultSharpenCistemHighResBfactor
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
