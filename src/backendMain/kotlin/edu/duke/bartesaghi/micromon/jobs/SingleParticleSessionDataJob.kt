package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.Backend
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
) : Job(userId, projectId, config), FilteredJob {

	val args = JobArgs<SingleParticleSessionDataArgs>()
	var latestMicrographId: String? = null

	companion object : JobInfo {

		override val config = SingleParticleSessionDataNodeConfig
		override val dataType = JobInfo.DataType.Micrograph

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
		SingleParticleSessionDataData(
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

		// authenticate the user for the session
		val user = Database.users.getUser(userId)
			?: throw NoSuchElementException("no logged in user")
		val session = user.authSessionForReadOrThrow(newestArgs.sessionId)

		// set the hidden args
		pypArgs.dataMode = "spr"
		pypArgs.dataParent = session.pypDir(session.newestArgs().pypNamesOrThrow()).toString()
		pypArgs.dataImport = true

		Pyp.pyp.launch(runId, pypArgs, "Import Single Particle Session", "pyp_import")

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
