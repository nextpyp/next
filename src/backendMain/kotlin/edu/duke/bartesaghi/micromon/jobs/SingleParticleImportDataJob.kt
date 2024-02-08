package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.SingleParticleImportDataNodeConfig
import edu.duke.bartesaghi.micromon.projects.Project
import edu.duke.bartesaghi.micromon.projects.RepresentativeImage
import edu.duke.bartesaghi.micromon.projects.RepresentativeImageType
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.waitForExistence
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.time.Duration.Companion.seconds


class SingleParticleImportDataJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), FilteredJob {

	val args = JobArgs<SingleParticleImportDataArgs>()
	var latestMicrographId: String? = null

	companion object : JobInfo {

		override val config = SingleParticleImportDataNodeConfig
		override val dataType = JobInfo.DataType.Micrograph

		override fun fromDoc(doc: Document) = SingleParticleImportDataJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			fromDoc(doc)
			args.finished = doc.getDocument("finishedArgs")?.let { SingleParticleImportDataArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { SingleParticleImportDataArgs.fromDoc(it) }
			latestMicrographId = doc.getString("latestMicrographId")
		}

		private fun SingleParticleImportDataArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
			doc["list"] = particlesName
		}

		private fun SingleParticleImportDataArgs.Companion.fromDoc(doc: Document) =
			SingleParticleImportDataArgs(
				doc.getString("values"),
				doc.getString("list")
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
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		SingleParticleImportDataData(
			commonData(),
			args,
			diagramImageURL(),
			Database.micrographs.count(idOrThrow),
			Database.particles.countAllParticles(idOrThrow, ParticlesList.PypAutoParticles)
		)

	override suspend fun launch(runId: Int) {

		// clear caches
		clearWwwCache()

		val newestArgs = args.newestOrThrow().args

		// if we've picked some particles, write those out to pyp
		newestArgs.particlesName
			?.let { Database.particleLists.get(idOrThrow, it) }
			?.let { ParticlesJobs.writeSingleParticle(idOrThrow, dir, it) }

		// build the args for PYP
		val pypArgs = ArgValues(Backend.pypArgs)

		// set the user args
		pypArgs.setAll(args().diff(
			newestArgs.values,
			args.finished?.values
		))

		// set the hidden args
		pypArgs.dataImport = true

		Pyp.pyp.launch(runId, pypArgs, "Import Single Particle", "pyp_import")

		// job was launched, move the args over
		args.run()
		// and wipe the latest micrograph id, so we can detect when the first one comes in next time
		latestMicrographId = null
		update()
	}

	override fun representativeImage() =
		// find an arbitrary (but deterministic) micrograph for this job
		// like the newest micrograph written for this job
		latestMicrographId?.let {
			RepresentativeImage(Document().apply {
				set("dataId", it)
			})
		}

	override fun representativeImageUrl(repImage: RepresentativeImage) =
		repImage.params?.getString("dataId")
			?.let { JobsService.dataImageUrl(idOrThrow, it, ImageSize.Small) }

	override suspend fun finished() {

		latestMicrographId?.let { micrographId ->
			// actually wait for the image file to show up in the filesystem,
			// since sometimes distributed filesystems (eg NFS) can be slower than pyp->website signals
			Micrograph.pypOutputImage(this, micrographId)
				.waitForExistence(15.seconds)
				.orWarn()
		}

		// update the representative image
		Project.representativeImages[userId, projectId, RepresentativeImageType.GainCorrected, idOrThrow] = representativeImage()
	}

	fun diagramImageURL(): String =
		representativeImage()?.let { representativeImageUrl(it) }
			?: "img/placeholder/${ImageSize.Small.id}"

	override val filters get() = PreprocessingFilters.ofJob(idOrThrow)

	override fun resolveFilter(filter: PreprocessingFilter): List<String> =
		FilteredJob.resolveFilterMicrographs(idOrThrow, filter)

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
