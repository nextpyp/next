package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.globCountOrNull
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.SingleParticleRelionDataNodeConfig
import edu.duke.bartesaghi.micromon.projects.Project
import edu.duke.bartesaghi.micromon.projects.RepresentativeImage
import edu.duke.bartesaghi.micromon.projects.RepresentativeImageType
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.waitForExistence
import org.bson.Document
import org.bson.conversions.Bson
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds


class SingleParticleRelionDataJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), FilteredJob {

	val args = JobArgs<SingleParticleRelionDataArgs>()

	companion object : JobInfo {

		override val config = SingleParticleRelionDataNodeConfig
		override val dataType = JobInfo.DataType.Micrograph

		override fun fromDoc(doc: Document) = SingleParticleRelionDataJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			fromDoc(doc)
			args.finished = doc.getDocument("finishedArgs")?.let { SingleParticleRelionDataArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { SingleParticleRelionDataArgs.fromDoc(it) }
		}

		private fun SingleParticleRelionDataArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun SingleParticleRelionDataArgs.Companion.fromDoc(doc: Document) =
			SingleParticleRelionDataArgs(
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
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		SingleParticleRelionDataData(
			commonData(),
			args,
			args.map { it.display() },
			diagramImageURL()
		)

	private suspend fun SingleParticleRelionDataArgs.display(): SingleParticleRelionDataDisplay {

		val values = values.toArgValues(args())

		// count the movies
		val numMovies = values.dataPath
			?.let { glob ->
				// lookup how many files match the glob
				Paths.get(glob).globCountOrNull()?.matched
			}

		return SingleParticleRelionDataDisplay(numMovies)
	}

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		val newestArgs = args.newestOrThrow().args

		// build the args for PYP
		val pypArgs = launchArgValues(null, newestArgs.values, args.finished?.values)

		// set the hidden args
		pypArgs.dataMode = "spr"
		pypArgs.importMode = "SPA_STAR"
		pypArgs.importReadStar = true

		Pyp.rlp.launch(project.osUsername, runId, pypArgs, "Import Star", "pyp_import")

		// job was launched, move the args over
		args.run()
		update()
	}

	override fun representativeImage() =
		RepresentativeImage()

	override fun representativeImageUrl(repImage: RepresentativeImage) =
		JobsService.gainCorrectedImageUrl(idOrThrow, ImageSize.Small)

	override suspend fun finished() {

		// actually wait for the image file to show up in the filesystem,
		// since sometimes distributed filesystems (eg NFS) can be slower than pyp->website signals
		GainCorrectedImage.path(this)
			.waitForExistence(15.seconds)
			.orWarn()

		// update the representative image
		Project.representativeImages[userId, projectId, RepresentativeImageType.GainCorrected, idOrThrow] = representativeImage()
	}

	override fun wipeData() {

		// no data to wipe

		// also reset the finished args
		args.unrun()
		update()
	}

	fun diagramImageURL(): String =
		representativeImageUrl(representativeImage())

	override val filters get() = PreprocessingFilters.ofJob(idOrThrow)

	override fun resolveFilter(filter: PreprocessingFilter): List<String> =
		FilteredJob.resolveFilterMicrographs(idOrThrow, filter)

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
