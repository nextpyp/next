package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographySessionDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.sessions.authSessionForReadOrThrow
import edu.duke.bartesaghi.micromon.sessions.pypNamesOrThrow
import org.bson.Document
import org.bson.conversions.Bson


class TomographySessionDataJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), FilteredJob {

	val args = JobArgs<TomographySessionDataArgs>()
	var latestTiltSeriesId: String? = null

	companion object : JobInfo {

		override val config = TomographySessionDataNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries

		override fun fromDoc(doc: Document) = TomographySessionDataJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			fromDoc(doc)
			args.finished = doc.getDocument("finishedArgs")?.let { TomographySessionDataArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographySessionDataArgs.fromDoc(it) }
			latestTiltSeriesId = doc.getString("latestTiltSeriesId")
		}

		private fun TomographySessionDataArgs.toDoc() = Document().also { doc ->
			doc["sessionId"] = sessionId
			doc["values"] = values
			doc["list"] = particlesName
		}

		private fun TomographySessionDataArgs.Companion.fromDoc(doc: Document) =
			TomographySessionDataArgs(
				doc.getString("sessionId"),
				doc.getString("values"),
				doc.getString("list")
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
		TomographySessionDataData(
			commonData(),
			args,
			diagramImageURL(),
			Database.tiltSeries.count(idOrThrow),
			Database.particles.countAllParticles(idOrThrow, ParticlesList.AutoParticles)
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreateAs(project.osUsername)

		val newestArgs = args.newestOrThrow().args

		// authenticate the user for the session
		val user = Database.users.getUser(userId)
			?: throw NoSuchElementException("no logged in user")
		val session = user.authSessionForReadOrThrow(newestArgs.sessionId)

		// build the args for PYP
		val pypArgs = launchArgValues(null, newestArgs.values, args.finished?.values)

		// set the hidden args
		pypArgs.dataMode = "tomo"
		pypArgs.dataParent = session.pypDir(session.newestArgs().pypNamesOrThrow()).toString()
		pypArgs.dataImport = true

		Pyp.pyp.launch(project.osUsername, runId, pypArgs, "Import Tomography Session", "pyp_import")

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

	override val filters get() = PreprocessingFilters.ofJob(idOrThrow)

	override fun resolveFilter(filter: PreprocessingFilter): List<String> =
		FilteredJob.resolveFilterTiltSeries(idOrThrow, filter)

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
