package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyImportDataNodeConfig
import edu.duke.bartesaghi.micromon.projects.Project
import edu.duke.bartesaghi.micromon.projects.RepresentativeImage
import edu.duke.bartesaghi.micromon.projects.RepresentativeImageType
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.waitForExistence
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.time.Duration.Companion.seconds


class TomographyImportDataJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), FilteredJob, TiltSeriesesJob {

	val args = JobArgs<TomographyImportDataArgs>()

	override var latestTiltSeriesId: String? = null
	override val eventListeners get() = Companion.eventListeners

	companion object : JobInfo {

		override val config = TomographyImportDataNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries
		override val dataClass = TomographyImportDataData::class

		override fun fromDoc(doc: Document) = TomographyImportDataJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			fromDoc(doc)
			args.finished = doc.getDocument("finishedArgs")?.let { TomographyImportDataArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographyImportDataArgs.fromDoc(it) }
			latestTiltSeriesId = doc.getString("latestTiltSeriesId")
		}

		private fun TomographyImportDataArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun TomographyImportDataArgs.Companion.fromDoc(doc: Document) =
			TomographyImportDataArgs(
				doc.getString("values")
			)

		val eventListeners = TiltSeriesEventListeners(this)
	}

	override fun createDoc(doc: Document) {
		super.createDoc(doc)
		doc["finishedArgs"] = args.finished?.toDoc()
		doc["nextArgs"] = args.next?.toDoc()
		doc["latestTiltSeriesId"] = null
	}

	override fun updateDoc(updates: MutableList<Bson>) {
		super.updateDoc(updates)
		updates.add(Updates.set("finishedArgs", args.finished?.toDoc()))
		updates.add(Updates.set("nextArgs", args.next?.toDoc()))
		updates.add(Updates.set("latestTiltSeriesId", latestTiltSeriesId))
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		TomographyImportDataData(
			commonData(),
			args,
			diagramImageURL(),
			Database.instance.tiltSeries.count(idOrThrow),
			Database.instance.particles.countAllParticles(idOrThrow, ParticlesList.AutoParticles)
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreate()

		// build the args for PYP
		val pypArgs = launchArgValues()
		pypArgs.dataImport = true

		Pyp.pyp.launch(project, runId, pypArgs, "Import Tomography", "pyp_import")

		// job was launched, move the args over
		args.run()
		// and wipe the latest tilt series id, so we can detect when the first one comes in next time
		latestTiltSeriesId = null
		update()
	}

	override fun representativeImage() =
		// find an arbitrary (but deterministic) tilt series for this job
		// like the newest micrograph written for this job
		latestTiltSeriesId?.let {
			RepresentativeImage(Document().apply {
				set("dataId", it)
			})
		}

	override fun representativeImageUrl(repImage: RepresentativeImage) =
		repImage.params?.getString("dataId")
			?.let { JobsService.dataImageUrl(idOrThrow, it, ImageSize.Small) }

	override suspend fun finished() {

		latestTiltSeriesId?.let { tiltSeriesId ->
			// actually wait for the image file to show up in the filesystem,
			// since sometimes distributed filesystems (eg NFS) can be slower than pyp->website signals
			TiltSeries.outputImagePath(dir, tiltSeriesId)
				.waitForExistence(15.seconds)
				.orWarn()
		}

		// update the representative image
		Project.representativeImages[userId, projectId, RepresentativeImageType.GainCorrected, idOrThrow] = representativeImage()
	}

	override fun wipeData() {

		// also delete any associated data
		Database.instance.tiltSeries.deleteAll(idOrThrow)
		Database.instance.tiltSeriesAvgRot.deleteAll(idOrThrow)
		Database.instance.tiltSeriesDriftMetadata.deleteAll(idOrThrow)
		Database.instance.jobPreprocessingFilters.deleteAll(idOrThrow)
		Database.instance.particleLists.deleteAll(idOrThrow)
		Database.instance.particles.deleteAllParticles(idOrThrow)
		Database.instance.tiltExclusions.delete(idOrThrow)

		// also reset the finished args
		args.unrun()
		latestTiltSeriesId = null
		update()
	}

	fun diagramImageURL(): String =
		representativeImage()?.let { representativeImageUrl(it) }
			?: "img/placeholder/${ImageSize.Small.id}"

	override val filters get() = PreprocessingFilters.ofJob(idOrThrow)

	override fun resolveFilter(filter: PreprocessingFilter): List<String> =
		FilteredJob.resolveFilterTiltSeries(idOrThrow, filter)

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
