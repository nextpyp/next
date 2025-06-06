package edu.duke.bartesaghi.micromon.sessions

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.slurm.toSbatchArgs
import edu.duke.bartesaghi.micromon.files.Speeds
import edu.duke.bartesaghi.micromon.linux.userprocessor.WebCacheDir
import edu.duke.bartesaghi.micromon.linux.userprocessor.writeStringAs
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.sessions.SingleParticleSession.Companion.StreampypListener
import io.ktor.application.*
import io.ktor.util.pipeline.*
import io.kvision.remote.ServiceException
import org.bson.Document
import org.bson.conversions.Bson
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.ArrayList
import java.util.NoSuchElementException
import kotlin.io.path.div
import kotlin.reflect.KClass


sealed class Session(
	val type: Type,
	val userId: String,
	val version: Version
) {

	enum class Version {

		/** old versions of pyp used group/name subfolders inside of the session folder */
		Subfolders,
		/** current versions of pyp use just one root folder now */
		RootFolder;

		companion object {
			fun newest(): Version = RootFolder
		}
	}

	var id: String? = null
		protected set
	val idOrThrow get() =
		id ?: throw NoSuchElementException("session has no id")

	var sessionNumber: Int? = null
		protected set

	var created: Instant? = null
	val createdOrThrow get() = created ?: throw NoSuchElementException("session has not been created yet")


	interface Type {
		val id: String
		val configId: String
		fun fromDoc(doc: Document): Session
		val events: SessionEvents
		fun init()
	}

	data class PypNames(
		val group: String,
		val session: String
	)

	data class Header(
		val sessionId: String,
		val userId: String
	)

	companion object {

		// NOTE: need to defer creating this list with lazy,
		//   otherwise kotlinc gets confused and makes the references to the companion objects null
		//   probably because of the circular references
		private val types: List<Type> by lazy {
			listOf(
				SingleParticleSession,
				TomographySession
			)
		}

		fun fromId(sessionId: String): Session? {
			val doc = Database.instance.sessions.get(sessionId) ?: return null
			return fromDoc(doc)
		}

		fun fromIdOrThrow(sessionId: String): Session =
			fromId(sessionId) ?: throw NoSuchElementException("no session with id=$sessionId")

		fun fromDoc(doc: Document): Session {
			val typeId = doc.getString("type")
				?: throw NoSuchElementException("type not set for session")
			val type = types
				.find { it.id == typeId }
				?: throw NoSuchElementException("unrecognized session type: $typeId")
			return type.fromDoc(doc)
		}

		fun headerFromDoc(doc: Document) = Header(
			sessionId = doc.getObjectId("_id").toStringId(),
			userId = doc.getString("userId")
		)

		fun defaultDir(): Path =
			Config.instance.web.sharedDir / "sessions"

		fun defaultDir(sessionId: String): Path =
			defaultDir() / sessionId

		/** get the session sub-directory that pyp uses, for some reason */
		fun pypDir(session: Session, names: PypNames): Path =
			session.dir / names.group / names.session

		fun init() {
			for (type in types) {
				type.init()
			}
		}
	}

	protected open fun createDoc(doc: Document) {

		val created = created
			?: throw IllegalStateException("set created timestamp before creating")

		doc["type"] = type.id
		doc["userId"] = userId
		doc["created"] = created.toEpochMilli()
	}

	protected open fun updateDoc(updates: MutableList<Bson>) {
		// TODO: anything that needs updating here?
	}

	protected fun fromDoc(doc: Document) {
		sessionNumber = doc.getInteger("sessionNumber")
		created = Instant.ofEpochMilli(doc.getLong("created"))
	}

	/**
	 * Create a new session in the database.
	 * Throws an error if this session has already been created
	 */
	suspend fun create() {
		// TODO: create (and later run) the session as any specific OS user?

		if (id != null) {
			throw IllegalStateException("session has already been created")
		}

		// make sure the folder doesn't already exist
		if (dir.exists()) {
			throw ServiceException("Session folder already exists: $dir")
		}

		created = Instant.now()

		// create the session
		val (id, number) = Database.instance.sessions.create {
			createDoc(this)
		}
		this.id = id
		this.sessionNumber = number

		// create the session folder, if needed
		dir.createDirsIfNeeded()
		wwwDir.createIfNeeded()
		LinkTree.sessionCreated(this, Database.instance.groups.getOrThrow(newestArgs().groupId))
	}

	fun update() {

		// get the old session from the database before changing it
		val oldSession = fromIdOrThrow(idOrThrow)

		// reject any edit that would change the path
		if (oldSession.dir != dir) {
			throw ServiceException("Edit rejected: path changed")
		}

		Database.instance.sessions.update(idOrThrow, ArrayList<Bson>().apply {
			updateDoc(this)
		})

		// look for changes to the group or name
		val oldArgs = oldSession.newestArgs()
		val newArgs = newestArgs()
		if (oldArgs.groupId != newArgs.groupId || oldArgs.name != newArgs.name) {
			val oldGroup = Database.instance.groups.get(oldArgs.groupId)
			// NOTE: the old group may have been deleted
			val newGroup = Database.instance.groups.getOrThrow(newArgs.groupId)
			LinkTree.sessionEdited(oldGroup, oldArgs.name, this, newGroup)
		}
	}

	open fun delete() {

		val id = id ?: throw IllegalStateException("session has no id")

		// delete the session from the database
		Database.instance.sessions.delete(id)

		// delete any folders/files associated with the session
		dir.deleteDirRecursively()
		LinkTree.sessionDeleted(this, Database.instance.groups.getOrThrow(newestArgs().groupId))

		this.id = null
	}

	/** return true iff this job has pending changes that need to be run */
	open fun isChanged(): Boolean = false

	fun isReadableBy(user: User): Boolean {

		// admins can read everything
		if (user.isAdmin) {
			return true
		}

		// so can users with the edit session permission
		if (User.Permission.EditSession in user.permissions) {
			return true
		}

		// otherwise, the session must be for one of the user's groups
		val groupId = newestArgs().groupId
		return user.groups.any { it.id == groupId }
	}

	abstract fun data(user: User?): SessionData

	val dir: Path get() =
		Paths.get(newestArgs().path)


	/** get the folder that pyp used to create files: varies depending on pyp version */
	fun pypDir(names: PypNames): Path =
		when (version) {
			Version.Subfolders -> dir / names.group / names.session
			Version.RootFolder -> dir
		}

	val wwwDir: WebCacheDir get() =
		WebCacheDir(dir / "www", null)

	fun pypParamsPath(): Path =
		dir / "pyp_params.toml"

	abstract fun newestArgs(): SessionArgs
	abstract fun newestArgValues(): ArgValuesToml?

	/** get the newest pyp values from the database entry for this session */
	abstract fun newestPypValues(): ArgValues

	fun findDaemonJobs(daemon: SessionDaemon): List<ClusterJob> =
		ClusterJob.find(idOrThrow, clusterName = daemon.clusterJobClusterName)

	fun findNonDaemonJobs(): List<ClusterJob> =
		ClusterJob.find(idOrThrow, notClusterName = SessionDaemon.values().map { it.clusterJobClusterName })


	protected fun launchArgValues(): ArgValues {

		val values = ArgValues(Backend.instance.pypArgsWithMicromon)

		// add arg values from this session
		val jobValues = (this.newestArgValues() ?: "")
			.toArgValues(Backend.instance.pypArgsWithMicromon)
		for (arg in values.args.args) {

			// skip args whose value is the default value
			if (arg.default != null && jobValues[arg] == arg.default.value) {
				continue
			}

			// otherwise, add the value
			values[arg] = jobValues[arg]
		}

		if (Backend.log.isDebugEnabled) {
			Backend.log.debug("""
				|launchArgValues()  session=$type  id=$idOrThrow
				|  ${values.toString().lines().joinToString("\n  ")}
			""".trimMargin())
		}

		return values
	}

	protected suspend fun launch(argValues: ArgValues, webName: String) {

		// write the parameters file
		val paramsPath = pypParamsPath()
		paramsPath.writeStringAs(null, argValues.toToml())

		// launch the cluster job
		Pyp.streampyp.launch(
			userId = null,
			osUsername = null,
			webName = webName,
			clusterName = SessionDaemon.Streampyp.clusterJobClusterName,
			owner = idOrThrow,
			ownerListener = StreampypListener,
			dir = dir,
			args = listOf("-params_file=$paramsPath"),
			launchArgs = argValues.toSbatchArgs(),
			template = argValues.slurmTemplate
		)
	}

	/** returns true iff the specified daemon is running */
	fun isRunning(daemon: SessionDaemon): Boolean {

		val runningJobs = findDaemonJobs(daemon)
			.filter { it.getLog()?.status() == ClusterJob.Status.Started }
		return when (runningJobs.size) {
			0 -> false
			1 -> true
			else -> {
				Backend.log.warn("${type.id} Session $id has ${runningJobs.size} $daemon jobs running")
				true
			}
		}
	}

	fun runningJobs(): List<ClusterJob> {

		val daemonNames = SessionDaemon.values()
			.map { it.clusterJobClusterName }
			.toSet()

		return ClusterJob.find(idOrThrow)
			.filter { it.clusterName !in daemonNames }
			.filter { it.getLog()?.status() == ClusterJob.Status.Started }
	}

	/** starts one of the daemons */
	abstract suspend fun start(daemon: SessionDaemon)

	/** restarts one of the daemons */
	suspend fun restart(daemon: SessionDaemon) {

		// only sub-daemons can be restarted
		if (!daemon.isSubDaemon) {
			return
		}

		writeSignalFile(daemon, "restart", params = true)
	}

	/** clear one of the daemons */
	suspend fun clear(daemon: SessionDaemon) {

		// only sub-daemons can be cleared
		if (!daemon.isSubDaemon) {
			return
		}

		// delete any 2D classes associated with the session
		Database.instance.twoDClasses.deleteAll(idOrThrow)

		writeSignalFile(daemon, "clear", params = true)
	}

	/** stops one of the daemons */
	suspend fun stop(daemon: SessionDaemon) {
		writeSignalFile(daemon, "stop")
	}

	/**
	 * A more powerful version of stop.
	 * This doesn't just ask the daemon to stop itself,
	 * it asks SLURM to forcibly terminate all jobs for this session.
	 */
	abstract suspend fun cancel()

	private suspend fun writeSignalFile(daemon: SessionDaemon, signal: String, params: Boolean = false) {

		// add pyp arguments to the signal file, if needed
		val payload = if (params) {
			launchArgValues().toToml()
		} else {
			""
		}

		slowIOs {

			// ask the daemon to stop nicely
			val pypDir = pypDir(newestArgs().pypNamesOrThrow())
			val path = pypDir / "${daemon.filename}.$signal"
			path.writeString(payload)
		}
	}


	data class TransferFolders(
		val source: Path?,
		val dest: Path
	)

	fun transferFolders(): TransferFolders? {

		// get the source folder, which might not have been chosen yet
		val src = newestPypValues().dataPath
			?.toPath()
			?.parent

		// get the destination folder
		val pypNames = newestArgs().pypNames()
			?: return null
		val pypDir = pypDir(pypNames)
		val dst = pypDir / "raw"

		return TransferFolders(src, dst)
	}

	fun pypParameters(): ArgValues? =
		Database.instance.parameters.getParams(idOrThrow)

	fun pypParametersOrThrow(): ArgValues =
		pypParameters()
			?: throw ServiceException("no pyp parameters available")

	fun readTransferSpeeds(): Speeds? {

		val names = newestArgs().pypNames()
			?: return null
		val path = pypDir(names) / "${names.session}_speed.txt"
		if (!path.exists()) {
			return null
		}

		return Speeds.from(path.readString())
	}

	/** return the data ids included by the filter */
	abstract fun resolveFilter(filter: PreprocessingFilter): List<String>
}

fun SessionArgs.pypNames(): Session.PypNames? {
	val group = Database.instance.groups.get(groupId)
		?: return null
	return Session.PypNames(
		group = group.name.toSafeFileName(group.idOrThrow),
		session = this.name.toSafeFileName()
	)
}

fun SessionArgs.pypNamesOrThrow(): Session.PypNames =
	pypNames()
		?: throw NoSuchElementException("can't lookup group name, no group found with id=$groupId")

fun User.authSessionForWriteOrThrow(sessionId: String): Session {
	authPermissionOrThrow(User.Permission.EditSession)
	val doc = Database.instance.sessions.get(sessionId)
		?: throw AuthException("no session with that id").withInternal("sessionId = $sessionId")
	return Session.fromDoc(doc)
}

fun User.authSessionForReadOrThrow(sessionId: String): Session {

	// get the session
	val doc = Database.instance.sessions.get(sessionId)
		?: throw AuthException("no session with that id").withInternal("sessionId = $sessionId")
	val session = Session.fromDoc(doc)

	// make sure the user has access to the group
	if (!session.isReadableBy(this)) {
		throw AuthException("access denied for group")
			.withInternal("user = $id, groups = $groups, session group = ${session.newestArgs().groupId}")
	}
	return session
}

fun User.authSessionOrThrow(sessionId: String, permission: SessionPermission): Session =
	when (permission) {
		SessionPermission.Read -> authSessionForReadOrThrow(sessionId)
		SessionPermission.Write -> authSessionForWriteOrThrow(sessionId)
	}

data class AuthInfo<T:Session>(
	val user: User,
	val session: T
)

inline fun <reified T:Session> Service.auth(sessionId: String, permission: SessionPermission): AuthInfo<T> =
	call.authSession(sessionId, permission)

inline fun <reified T:Session> ApplicationCall.authSession(sessionId: String, permission: SessionPermission): AuthInfo<T> {
	val user = authOrThrow()
	val session = user.authSessionOrThrow(sessionId, permission).cast<T>()
	return AuthInfo(user, session)
}


fun User?.permissions(session: Session): Set<SessionPermission> {

	val user = this
		?: return emptySet()

	return HashSet<SessionPermission>().apply {

		if (user.isAdmin || hasPermission(User.Permission.EditSession)) {
			add(SessionPermission.Write)
		}

		if (session.isReadableBy(user)) {
			add(SessionPermission.Read)
		}
	}
}


inline fun <reified T:Session> Session.cast(): T =
	this as? T
		?: throw WrongSessionTypeException(this, T::class)

class WrongSessionTypeException(val session: Session, val expectedType: KClass<*>)
	: IllegalArgumentException("expected session ${session.id} to be a $expectedType, not a ${session::class}")


fun User.authSessionClusterJobOrThrow(session: Session, clusterJobId: String): ClusterJob {

	fun deny(msg: String): Nothing {
		throw AuthException("cluster job not authorized")
			.withInternal("userId = $id, sessionId = ${session.id}, clusterJobId = $clusterJobId, $msg")
	}

	val clusterJob = ClusterJob.get(clusterJobId)
		?: deny("cluster job doesn't exist")
	val ownerId = clusterJob.ownerId
		?: deny("cluster job has no owner")

	// check for session ownership
	if (ownerId == session.id) {
		return clusterJob
	}

	// check for export ownership
	if (SessionExport.get(ownerId) != null) {
		return clusterJob
	}

	deny("cluster job owned by neither session nor export")
}
