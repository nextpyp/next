package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.SingleParticleSessionDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.sessions.authSessionForReadOrThrow
import edu.duke.bartesaghi.micromon.sessions.pypNamesOrThrow
import org.bson.Document
import org.bson.conversions.Bson


class SingleParticleSessionDataJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), FilteredJob, MicrographsJob {

	val args = JobArgs<SingleParticleSessionDataArgs>()
	override var latestMicrographId: String? = null
	override val eventListeners get() = Companion.eventListeners

	companion object : JobInfo {

		override val config = SingleParticleSessionDataNodeConfig
		override val dataType = JobInfo.DataType.Micrograph
		override val dataClass = SingleParticleSessionDataData::class

		override fun fromDoc(doc: Document) = SingleParticleSessionDataJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			fromDoc(doc)
			args.finished = doc.getDocument("finishedArgs")?.let { SingleParticleSessionDataArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { SingleParticleSessionDataArgs.fromDoc(it) }
			latestMicrographId = doc.getString("latestMicrographId")
		}

		private fun SingleParticleSessionDataArgs.toDoc() = Document().also { doc ->
			doc["sessionId"] = sessionId
			doc["values"] = values
			doc["list"] = particlesName
		}

		private fun SingleParticleSessionDataArgs.Companion.fromDoc(doc: Document) =
			SingleParticleSessionDataArgs(
				doc.getString("sessionId"),
				doc.getString("values"),
				doc.getString("list")
			)

		val eventListeners = MicrographEventListeners(this)
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
		SingleParticleSessionDataData(
			commonData(),
			args,
			diagramImageURL(),
			Database.instance.micrographs.count(idOrThrow),
			Database.instance.particles.countAllParticles(idOrThrow, ParticlesList.AutoParticles)
		)

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreate()

		val newestArgs = args.newestOrThrow().args

		// authenticate the user for the session
		val user = Database.instance.users.getUser(userId)
			?: throw NoSuchElementException("no logged in user")
		val session = user.authSessionForReadOrThrow(newestArgs.sessionId)

		// build the args for PYP
		val pypArgs = launchArgValues()
		pypArgs.dataMode = "spr"
		pypArgs.dataParent = session.pypDir(session.newestArgs().pypNamesOrThrow()).toString()
		pypArgs.dataImport = true

		Pyp.pyp.launch(project, runId, pypArgs, "Import Single Particle Session", "pyp_import")

		// job was launched, move the args over
		args.run()
		// and wipe the latest micrograph id, so we can detect when the first one comes in next time
		latestMicrographId = null
		update()
	}

	fun diagramImageURL(): String {

		val size = ImageSize.Small

		// find an arbitrary (but deterministic) micrograph for this job
		// like the newest micrograph written for this job
		return latestMicrographId
			?.let { "/kv/jobs/$idOrThrow/data/$it/image/${size.id}" }

			// or just use a placeholder
			?: return "/img/placeholder/${size.id}"
	}

	override val filters get() = PreprocessingFilters.ofJob(idOrThrow)

	override fun resolveFilter(filter: PreprocessingFilter): List<String> =
		FilteredJob.resolveFilterMicrographs(idOrThrow, filter)

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
