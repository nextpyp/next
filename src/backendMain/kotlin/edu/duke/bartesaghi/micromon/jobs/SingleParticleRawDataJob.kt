package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.globCountOrNull
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.SingleParticleRawDataNodeConfig
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


class SingleParticleRawDataJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<SingleParticleRawDataArgs>()

	companion object : JobInfo {

		override val config = SingleParticleRawDataNodeConfig
		override val dataType = null

		override fun fromDoc(doc: Document) = SingleParticleRawDataJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			fromDoc(doc)
			args.finished = doc.getDocument("finishedArgs")?.let { SingleParticleRawDataArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { SingleParticleRawDataArgs.fromDoc(it) }
		}

		private fun SingleParticleRawDataArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun SingleParticleRawDataArgs.Companion.fromDoc(doc: Document) =
			SingleParticleRawDataArgs(
				doc.getString("values")
			)

		fun args() =
			Backend.pypArgs
				.filter(config.configId, includeHiddenArgs = false, includeHiddenGroups = true)
				// no slurm tab here apparently, so don't need micromon slurm args
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
		SingleParticleRawDataData(
			commonData(),
			args,
			args.map { it.display() },
			diagramImageURL()
		)

	private suspend fun SingleParticleRawDataArgs.display(): SingleParticleRawDataDisplay {

		val values = values.toArgValues(args())

		// count the movies
		val numMovies = values.dataPath
			?.let { glob ->
				// lookup how many files match the glob
				Paths.get(glob).globCountOrNull()?.matched
			}

		return SingleParticleRawDataDisplay(numMovies)
	}

	override suspend fun launch(runId: Int) {

		// clear caches
		clearWwwCache()

		// build the args for PYP
		val pypArgs = ArgValues(Backend.pypArgs)

		// set the user args
		pypArgs.setAll(args().diff(
			args.newestOrThrow().args.values,
			args.finished?.values
		))

		// set the hidden args
		pypArgs.dataMode = "spr"

		Pyp.gyp.launch(runId, pypArgs, "Check gain reference", "pyp_gainref")

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

	fun diagramImageURL(): String =
		representativeImageUrl(representativeImage())

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
