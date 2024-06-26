package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.globCountOrNull
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyRawDataNodeConfig
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


class TomographyRawDataJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<TomographyRawDataArgs>()

	companion object : JobInfo {

		override val config = TomographyRawDataNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries

		override fun fromDoc(doc: Document) = TomographyRawDataJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			fromDoc(doc)
			args.finished = doc.getDocument("finishedArgs")?.let { TomographyRawDataArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographyRawDataArgs.fromDoc(it) }
		}

		private fun TomographyRawDataArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun TomographyRawDataArgs.Companion.fromDoc(doc: Document) =
			TomographyRawDataArgs(
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
		TomographyRawDataData(
			commonData(),
			args,
			args.map { it.display() },
			diagramImageURL()
		)

	private suspend fun TomographyRawDataArgs.display(): TomographyRawDataDisplay {

		val values = values.toArgValues(args())

		// count the tilt series
		val numTiltSeries = values.dataPath
			?.let { glob ->
				// lookup how many files match the glob
				Paths.get(glob).globCountOrNull()?.matched
			}

		return TomographyRawDataDisplay(numTiltSeries)
	}

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		// build the args for PYP
		val pypArgs = ArgValues(Backend.pypArgs)

		// set the user args
		pypArgs.setAll(args().diff(
			args.newestOrThrow().args.values,
			args.finished?.values
		))

		// set the hidden args
		pypArgs.dataMode = "tomo"

		Pyp.gyp.launch(project.osUsername, runId, pypArgs, "Check gain reference", "pyp_gainref")

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
