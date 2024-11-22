package edu.duke.bartesaghi.micromon.sessions

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.io.path.div


class SingleParticleSession(
	userId: String
) : Session(Companion, userId) {

	companion object : Type {

		override val id = SingleParticleSessionData.ID
		override val configId = "stream_spr"

		override fun fromDoc(doc: Document) = SingleParticleSession(
			doc.getString("userId")
		).apply {
			fromDoc(doc)
			args.finished = doc.getDocument("finishedArgs")?.let { SingleParticleSessionArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { SingleParticleSessionArgs.fromDoc(it) }
		}

		private fun SingleParticleSessionArgs.toDoc() = Document().also { doc ->
			doc["name"] = name
			doc["groupId"] = groupId
			doc["values"] = values
		}

		private fun SingleParticleSessionArgs.Companion.fromDoc(doc: Document) =
			SingleParticleSessionArgs(
				doc.getString("name"),
				doc.getString("groupId"),
				doc.getString("values")
			)

		fun fromId(sessionId: String): SingleParticleSession? =
			Session.fromId(sessionId) as SingleParticleSession?

		fun fromIdOrThrow(sessionId: String): SingleParticleSession =
			Session.fromIdOrThrow(sessionId) as SingleParticleSession

		fun args() =
			Backend.instance.pypArgs
				.filter(configId)
				.appendAll(MicromonArgs.slurmLaunch)

		object StreampypListener : ClusterJob.OwnerListener {

			override val id: String get() = SingleParticleSessionData.ID + "-session"

			override suspend fun ended(ownerId: String, resultType: ClusterJobResultType) {
				events.sessionFinished(ownerId)
			}
		}

		override val events = SessionEvents()

		override fun init() {
			ClusterJob.ownerListeners.add(StreampypListener)
		}
	}


	val args = JobArgs<SingleParticleSessionArgs>()

	override fun newestArgs(): SingleParticleSessionArgs =
		args.newestOrThrow().args

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun newestPypValues(): ArgValues =
		args.newestOrThrow().args.values.toArgValues(Backend.instance.pypArgs)

	override fun data(user: User?): SingleParticleSessionData {
		val (numMicrographs, numFrames) = Database.instance.micrographs.counts(idOrThrow)
		return SingleParticleSessionData(
			userId,
			idOrThrow,
			sessionNumber,
			createdOrThrow.toEpochMilli(),
			user.permissions(this),
			dir.toString(),
			args,
			args.map { args ->
				val group = Database.instance.groups.get(args.groupId)
				SingleParticleSessionDisplay(
					groupName = group?.name ?: "(unknown group)"
				)
			},
			numMicrographs = numMicrographs,
			numFrames = numFrames
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

	override suspend fun cancel() {
		Pyp.cancel(idOrThrow)
	}

	override suspend fun start(daemon: SessionDaemon) {

		// only the main daemon can be started
		if (!daemon.isMainDaemon) {
			return
		}

		// make the session sub-directory for pyp
		val names = args.newestOrThrow().args.pypNamesOrThrow()
		val pypDir = pypDir(names)
		pypDir.createDirsIfNeeded()

		// also make the "raw" directory now, so we can watch for file transfers
		(pypDir / "raw").createDirsIfNeeded()

		// build the args for PYP
		val pypArgs = launchArgValues()
		pypArgs.dataMode = "spr"
		pypArgs.streamTransferTarget = dir.toString()
		pypArgs.streamSessionGroup = names.group
		pypArgs.streamSessionName = names.session

		// is the pyp daemon already running from a previous run?
		if (isRunning(SessionDaemon.Pypd)) {

			// yup, don't start it again by sending the local flag to nextpyp
			pypArgs.streamTransferLocal = true
		}

		events.sessionStarted(idOrThrow)

		launch(pypArgs, "Single Particle Session")

		// daemon was started, move the args over
		args.run()
		update()
	}

	override fun resolveFilter(filter: PreprocessingFilter): List<String> =
		Database.instance.micrographs.getAll(idOrThrow) { cursor ->
			cursor
				.map { Micrograph(it) }
				.filter { it.isInRanges(filter) && it.micrographId !in filter.excludedIds }
				.map { it.micrographId }
				.toList()
		}
}
