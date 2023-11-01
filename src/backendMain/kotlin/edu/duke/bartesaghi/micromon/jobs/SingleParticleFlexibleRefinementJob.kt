package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.SingleParticleFlexibleRefinementNodeConfig
import edu.duke.bartesaghi.micromon.projects.Project
import edu.duke.bartesaghi.micromon.projects.RepresentativeImage
import edu.duke.bartesaghi.micromon.projects.RepresentativeImageType
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson


class SingleParticleFlexibleRefinementJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<SingleParticleFlexibleRefinementArgs>()
	var inRefinements: CommonJobData.DataId? by InputProp(config.refinements)
	var latestReconstructionId: String?  = null
	var jobInfoString: String? = null

	companion object : JobInfo {

		override val config = SingleParticleFlexibleRefinementNodeConfig
		override val dataType = null

		override fun fromDoc(doc: Document) = SingleParticleFlexibleRefinementJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { SingleParticleFlexibleRefinementArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { SingleParticleFlexibleRefinementArgs.fromDoc(it) }
			latestReconstructionId = doc.getString("latestReconstructionId")
			jobInfoString = doc.getString("jobInfoString")
			fromDoc(doc)
		}

		private fun SingleParticleFlexibleRefinementArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun SingleParticleFlexibleRefinementArgs.Companion.fromDoc(doc: Document) =
			SingleParticleFlexibleRefinementArgs(
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
		updates.add(Updates.set("jobInfoString", jobInfoString))
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		SingleParticleFlexibleRefinementData(
			commonData(),
			args,
			diagramImageURL(),
			jobInfoString ?: ""
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
		pypArgs.extractFmt = "frealign_local"

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
