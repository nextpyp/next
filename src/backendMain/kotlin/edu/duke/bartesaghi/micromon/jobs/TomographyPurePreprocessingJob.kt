package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.createDirsIfNeededAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.deleteDirRecursivelyAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.writerAs
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyPurePreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.io.path.div


class TomographyPurePreprocessingJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), FilteredJob, TiltSeriesesJob {

	val args = JobArgs<TomographyPurePreprocessingArgs>()
	override var latestTiltSeriesId: String? = null
	override val eventListeners get() = Companion.eventListeners

	var inTiltSeries: CommonJobData.DataId? by InputProp(config.tiltSeries)

	companion object : JobInfo {

		override val config = TomographyPurePreprocessingNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries
		override val dataClass = TomographyPurePreprocessingData::class

		override fun fromDoc(doc: Document) = TomographyPurePreprocessingJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { TomographyPurePreprocessingArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographyPurePreprocessingArgs.fromDoc(it) }
			latestTiltSeriesId = doc.getString("latestTiltSeriesId")
			fromDoc(doc)
		}

		private fun TomographyPurePreprocessingArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun TomographyPurePreprocessingArgs.Companion.fromDoc(doc: Document) =
			TomographyPurePreprocessingArgs(
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
		TomographyPurePreprocessingData(
			commonData(),
			args,
			diagramImageURL(),
			Database.instance.tiltSeries.count(idOrThrow)
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreate()

		// write out the tilt exclusions, if needed
		run {

			val dir = dir / "next"
			val suffix = "_exclude_views.next"

			// delete any old files
			dir.deleteDirRecursivelyAs(project.osUsername)
			dir.createDirsIfNeededAs(project.osUsername)

			// write any new files
			val exclusionsByTiltSeries = Database.instance.tiltExclusions.getForJob(idOrThrow)
			if (exclusionsByTiltSeries != null) {
				for ((tiltSeriesId, exclusionsByTiltIndex) in exclusionsByTiltSeries) {
					val file = dir / "$tiltSeriesId$suffix"
					file.writerAs(project.osUsername).use { writer ->
						for ((tiltIndex, isExcluded) in exclusionsByTiltIndex) {
							if (isExcluded) {
								writer.write("0\t0\t$tiltIndex\n")
							}
						}
					}
				}
			}
		}

		// build the args for PYP
		val pypArgs = launchArgValues()
		pypArgs.dataMode = "tomo"

		Pyp.pyp.launch(project, runId, pypArgs, "Launch", "pyp_launch")

		// job was launched, move the args over
		args.run()
		// and wipe the latest tilt series id, so we can detect when the first one comes in next time
		latestTiltSeriesId = null
		update()
	}

	fun diagramImageURL(): String {

		val size = ImageSize.Small

		// find an arbitrary (but deterministic) tilt series for this job
		// like the newest tilt series written for this job
		return latestTiltSeriesId
			?.let { "/kv/jobs/$idOrThrow/data/$it/image/${size.id}" }

			// or just use a placeholder
			?: return "/img/placeholder/${size.id}"
	}

	override fun wipeData() {

		// also delete any associated data
		Database.instance.tiltSeries.deleteAll(idOrThrow)
		Database.instance.tiltSeriesAvgRot.deleteAll(idOrThrow)
		Database.instance.tiltSeriesDriftMetadata.deleteAll(idOrThrow)
		Database.instance.jobPreprocessingFilters.deleteAll(idOrThrow)
		Database.instance.tiltExclusions.delete(idOrThrow)

		// also reset the finished args
		args.unrun()
		latestTiltSeriesId = null
		update()
	}

	override val filters get() = PreprocessingFilters.ofJob(idOrThrow)

	override fun resolveFilter(filter: PreprocessingFilter): List<String> =
		FilteredJob.resolveFilterTiltSeries(idOrThrow, filter)

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
