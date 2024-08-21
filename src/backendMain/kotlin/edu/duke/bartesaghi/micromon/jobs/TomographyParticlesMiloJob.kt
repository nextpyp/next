package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.linux.userprocessor.createDirsIfNeededAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.writeStringAs
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyParticlesMiloNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.io.path.div


class TomographyParticlesMiloJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<TomographyParticlesMiloArgs>()

	var inTomograms: CommonJobData.DataId? by InputProp(config.tomograms)

	companion object : JobInfo {

		override val config = TomographyParticlesMiloNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries

		override fun fromDoc(doc: Document) = TomographyParticlesMiloJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { TomographyParticlesMiloArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographyParticlesMiloArgs.fromDoc(it) }
			fromDoc(doc)
		}

		private fun TomographyParticlesMiloArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
			doc["filter"] = filter
		}

		private fun TomographyParticlesMiloArgs.Companion.fromDoc(doc: Document) =
			TomographyParticlesMiloArgs(
				doc.getString("values"),
				doc.getString("filter")
			)

		val eventListeners = TiltSeriesEventListeners(this)
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
		TomographyParticlesMiloData(
			commonData(),
			args,
			diagramImageURL()
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		// build the args for PYP
		val upstreamJob = inTomograms?.resolveJob<Job>()
			?: throw IllegalStateException("no tomograms input configured")

		// are we using a tomogram filter?
		if (upstreamJob is FilteredJob) {
			val filter = args.newestOrThrow().args.filter
				?.let { filter -> upstreamJob.filters[filter] }
			if (filter != null) {

				// write out the micrographs file to the job folder before starting pyp
				val dir = dir.createDirsIfNeededAs(project.osUsername)
				val file = dir / "${dir.fileName}.micrographs_subset"
				val micrographIds = upstreamJob.resolveFilter(filter)
				file.writeStringAs(project.osUsername, micrographIds.joinToString("\n"))
			}
		}

		val pypArgs = launchArgValues(upstreamJob, args.newestOrThrow().args.values, args.finished?.values)

		// set the hidden args
		pypArgs.dataMode = "tomo"
		pypArgs.dataParent = upstreamJob.dir.toString()

		Pyp.pyp.launch(project.osUsername, runId, pypArgs, "Launch", "pyp_launch")

		// job was launched, move the args over
		args.run()
		update()
	}

	fun diagramImageURL(): String =
		IJobsService.miloResults2dPath(idOrThrow, ImageSize.Small)

	override fun wipeData() {

		// TODO: also delete any associated data?
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
