package edu.duke.bartesaghi.micromon.sessions

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.slurm.toSbatchArgs
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.io.path.div


class TomographySession(
	userId: String
) : Session(Companion, userId) {

	companion object : Type {

		override val id = TomographySessionData.ID

		override fun fromDoc(doc: Document) = TomographySession(
			doc.getString("userId")
		).apply {
			fromDoc(doc)
			args.finished = doc.getDocument("finishedArgs")?.let { TomographySessionArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographySessionArgs.fromDoc(it) }
		}

		private fun TomographySessionArgs.toDoc() = Document().also { doc ->
			doc["name"] = name
			doc["groupId"] = groupId
			doc["values"] = values
		}

		private fun TomographySessionArgs.Companion.fromDoc(doc: Document) =
			TomographySessionArgs(
				doc.getString("name"),
				doc.getString("groupId"),
				doc.getString("values")
			)

		fun fromId(sessionId: String): TomographySession? =
			Session.fromId(sessionId) as TomographySession?

		fun fromIdOrThrow(sessionId: String): TomographySession =
			Session.fromIdOrThrow(sessionId) as TomographySession

		fun args() =
			Backend.pypArgs
				.filter("stream_tomo", includeHiddenArgs = false, includeHiddenGroups = true)
				.appendAll(MicromonArgs.slurmLaunch)

		object StreampypListener : ClusterJob.OwnerListener {

			override val id: String get() = TomographySessionData.ID + "-session"

			override suspend fun ended(ownerId: String, resultType: ClusterJobResultType) {
				events.sessionFinished(ownerId)
			}
		}

		override val events = SessionEvents()

		override fun init() {
			ClusterJob.ownerListeners.add(StreampypListener)
		}
	}


	val args = JobArgs<TomographySessionArgs>()

	override fun newestArgs(): TomographySessionArgs =
		args.newestOrThrow().args

	override fun newestPypValues(): ArgValues =
		args.newestOrThrow().args.values.toArgValues(Backend.pypArgs)

	override fun data(user: User?) = TomographySessionData(
		userId,
		idOrThrow,
		sessionNumber,
		createdOrThrow.toEpochMilli(),
		user.permissions(this),
		dir.toString(),
		args,
		args.map { args ->
			val group = Database.groups.get(args.groupId)
			TomographySessionDisplay(
				groupName = group?.name ?: "(unknown group)"
			)
		},
		numTiltSeries = Database.tiltSeries.count(idOrThrow),
		numTilts = Database.tiltSeriesDriftMetadata.countTilts(idOrThrow)
	)

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

	override fun argsDiff(): ArgValues {

		// build the args for PYP
		val sessionArgs = args.newestOrThrow().args
		val pypArgs = ArgValues(Backend.pypArgs)
		pypArgs.setAll(args().diff(
			sessionArgs.values,
			args.finished?.values
		))

		return pypArgs
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
		val pypArgs = argsDiff()
		pypArgs.dataMode = "tomo"
		pypArgs.streamTransferTarget = dir.toString()
		pypArgs.streamSessionGroup = names.group
		pypArgs.streamSessionName = names.session

		// is the pyp daemon already running from a previous run?
		if (isRunning(SessionDaemon.Pypd)) {

			// yup, don't start it again by sending the local flag to nextpyp
			pypArgs.streamTransferLocal = true
		}

		events.sessionStarted(idOrThrow)

		Pyp.streampyp.launch(
			webName = "Tomography Session",
			clusterName = SessionDaemon.Streampyp.clusterJobClusterName,
			owner = idOrThrow,
			ownerListener = StreampypListener,
			dir = dir,
			args = pypArgs.toPypCLI(),
			launchArgs = pypArgs.toSbatchArgs()
		)

		// daemon was started, move the args over
		args.run()
		update()
	}

	override fun resolveFilter(filter: PreprocessingFilter): List<String> =
		Database.tiltSeries.getAll(idOrThrow) { cursor ->
			cursor
				.map { TiltSeries(it) }
				.filter { it.isInRanges(filter) && it.tiltSeriesId !in filter.excludedIds }
				.map { it.tiltSeriesId }
				.toList()
		}
}
