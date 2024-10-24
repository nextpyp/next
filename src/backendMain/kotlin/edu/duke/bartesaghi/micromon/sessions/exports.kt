package edu.duke.bartesaghi.micromon.sessions

import com.mongodb.client.model.Updates.set
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.slurm.toSbatchArgs
import edu.duke.bartesaghi.micromon.linux.userprocessor.createDirsIfNeededAs
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.ArgValues
import edu.duke.bartesaghi.micromon.pyp.Pyp
import edu.duke.bartesaghi.micromon.pyp.dataParent
import edu.duke.bartesaghi.micromon.pyp.toPypCLI
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.div
import kotlin.io.path.relativeTo
import kotlin.reflect.KClass


class SessionExport(
	val sessionId: String,
	val request: SessionExportRequest,
	val created: Instant = Instant.now(),
	var clusterJobId: String? = null,
	var result: SessionExportResult? = null
) {

	var id: String? = null
		private set
	val idOrThrow get() = id
		?: throw java.util.NoSuchElementException("session export has no id")

	companion object : ClusterJob.OwnerListener {

		override val id = "session-exports"

		private suspend fun Session.fireEvents(export: SessionExport) {
			val events = when (this) {
				is SingleParticleSession -> SingleParticleSession.events
				is TomographySession -> TomographySession.events
			}
			for (listener in events.getListeners(idOrThrow)) {
				listener.onExport?.invoke(export)
			}
		}

		suspend fun launch(user: User, session: Session, request: SessionExportRequest, slurmArgValues: ArgValues) {

			// create the export
			val export = SessionExport(session.idOrThrow, request)
			export.id = Database.instance.sessionExports.create {
				set("sessionId", export.sessionId)
				set("request", export.request.serialize())
				set("created", export.created.toEpochMilli())
			}
			session.fireEvents(export)

			fun fail(reason: String) {
				Database.instance.sessionExports.update(export.idOrThrow,
					set("result", SessionExportResult.Failed(reason).serialize())
				)
			}

			// create the export folder
			export.dir.createDirsIfNeededAs(user.osUsername)

			// run the request-specific prep
			request.handler.prep(session, export)

			// calculate the session subfolder that pyp uses
			val names = session.newestArgs().pypNames()
				?: run {
					fail("Failed to compute session names for pyp")
					return
				}
			val pypDir = session.pypDir(names)

			// launch the cluster job
			val clusterJob = Pyp.pex.launch(
				osUsername = null,
				webName = "Export ${request::class.simpleName}",
				clusterName = "pyp_export",
				owner = export.idOrThrow,
				ownerListener = this,
				dir = export.dir,
				args = ArgValues(Backend.pypArgs).apply {
						dataParent = pypDir.toString()
					}.toPypCLI(),
				launchArgs = slurmArgValues.toSbatchArgs()
			)
			export.clusterJobId = clusterJob.id

			// update the database
			Database.instance.sessionExports.update(export.idOrThrow,
				set("clusterJobId", export.clusterJobId),
			)
			session.fireEvents(export)
		}

		fun get(exportId: String): SessionExport? =
			Database.instance.sessionExports.get(exportId)
				?.let { fromDoc(it) }

		fun getAll(sessionId: String): List<SessionExport> =
			Database.instance.sessionExports.getAll(sessionId) {
				it
					.map { fromDoc(it) }
					.toList()
			}

		fun fromDoc(doc: Document) = SessionExport(
			sessionId = doc.getString("sessionId")
				?: throw NoSuchElementException("sessionId not set for session export"),
			request = doc.getString("request")
				?.let { SessionExportRequest.deserialize(it) } // TODO: do we need to catch deserialization exceptions here?
				?: throw NoSuchElementException("request not set for session export"),
			created = doc.getLong("created")
				?.let { Instant.ofEpochMilli(it) }
				?: throw NoSuchElementException("created not set for session export"),
			clusterJobId = doc.getString("clusterJobId"),
			result = doc.getString("result")
				?.let { SessionExportResult.deserialize(it) } // TODO: do we need to catch deserialization exceptions here?
		).apply {
			id = doc.getObjectId("_id").toStringId()
		}

		fun dir(sessionId: String, exportId: String): Path =
			Session.dir(sessionId) / "exports" / exportId

		override suspend fun ended(ownerId: String, resultType: ClusterJobResultType) {

			val export = get(ownerId)
				?: return
			val session = Session.fromId(export.sessionId)
				?: return

			// if we already have a result, ignore other changes
			if (export.result != null) {
				return
			}

			// record the result
			val result = when (resultType) {
				ClusterJobResultType.Success -> export.request.handler.result(session, export)
				ClusterJobResultType.Failure -> SessionExportResult.Failed("The cluster job failed")
				ClusterJobResultType.Canceled -> SessionExportResult.Canceled()
			}
			export.result = result
			Database.instance.sessionExports.update(export.idOrThrow,
				set("result", result.serialize())
			)
			session.fireEvents(export)
		}

		fun init() {
			// register as a ClusterJob owner listener
			ClusterJob.ownerListeners.add(this)
		}
	}

	val dir: Path get() =
		dir(sessionId, idOrThrow)

	fun toData() = SessionExportData(
		sessionId,
		idOrThrow,
		request.serialize(),
		created.toEpochMilli(),
		clusterJobId,
		result?.serialize()
	)

	suspend fun cancel(session: Session) {

		// just in case ...
		if (sessionId != session.id) {
			throw Error("wrong session for export")
		}

		// if we already have a result, we can't cancel
		if (result != null) {
			return
		}

		// cancel the export locally
		val result = SessionExportResult.Canceled()
		this.result = result
		Database.instance.sessionExports.update(idOrThrow,
			set("result", result.serialize())
		)
		session.fireEvents(this)

		// try to cancel the cluster job, if needed
		Pyp.cancel(idOrThrow)
	}
}


class SessionExportHandler(
	val prep: (Session, SessionExport) -> Unit,
	val result: (Session, SessionExport) -> SessionExportResult
)

private val exportHandlers = mapOf<KClass<out SessionExportRequest>,SessionExportHandler>(

	SessionExportRequest.Filter::class to SessionExportHandler(

		prep = f@{ session, export ->
			export.request as SessionExportRequest.Filter

			// load the data filter
			val filter = PreprocessingFilters.ofSession(session.idOrThrow)
				.get(export.request.name)
				?: return@f

			// write the micrographs file to the export dir
			// yeah, use this file name even for tilt series
			val names = session.newestArgs().pypNames()
				?: return@f
			val file = export.dir / "${names.session}.micrographs"
			file.writeString(session.resolveFilter(filter).joinToString("\n"))
		},

		result = { session, export ->
			export.request as SessionExportRequest.Filter

			// look for the relion folder created by pyp
			val path = export.dir / "relion"

			if (!path.exists() && !path.isDirectory()) {
				SessionExportResult.Failed("The exported files were not found.")
			} else {
				val relPath = path.relativeTo(session.dir).toString()
				SessionExportResult.Succeeded(SessionExportResult.Succeeded.Output.Filter(relPath))
			}
		}
	)
)

private val SessionExportRequest.handler: SessionExportHandler get() =
	exportHandlers[this::class]
		?: throw NoSuchElementException("no session export request handler defined for ${this::class.simpleName}")
