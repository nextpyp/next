package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.auth.dir
import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.linux.Filesystem
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.projects.Project
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.pipeline.*
import io.kvision.remote.RemoteOption
import io.kvision.remote.ServiceException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.*
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink


actual class FormService : IFormService, Service {

	companion object {

		fun init(routing: Routing) {

			routing.route("kv/form") {

				post("fileBrowserFolderFast") {
					call.respondExceptions {

						val path = call.receiveText()

						// NOTE: this needs to handle 100s of thousands of directories efficiently
						// this needs to stay simple and optimized!!
						val json: String = service.fileBrowserFolder(path)
							.let { Json.encodeToString(it) }

						call.respondText(json, ContentType.Application.Json)
					}
				}
			}
		}

		private val PipelineContext<Unit,ApplicationCall>.service get() =
			getService<FormService>()
	}


	@Inject
	override lateinit var call: ApplicationCall

	private fun User.allowedRoots(): List<Path> {

		val out = ArrayList<Path>()

		// TODO: have a real user filesystem permissions system of some kind

		// allow all binds configured by the administrator
		out.addAll(Backend.config.pyp.binds)

		// allow the user's folder
		out.add(dir())

		return out
	}

	override suspend fun fileBrowserInit(): FileBrowserInit = sanitizeExceptions {

		val user = call.authOrThrow()

		val paths = user.allowedRoots()

		return FileBrowserInit(
			dataDirs = FileBrowserEntries(
				names = paths.map { it.toString() },
				typeIds = paths.map { FileBrowserType.Folder.ordinal }
			),
			homeDir = user.dir().toString(),
			projectsDir = Project.dir(user.id).toString()
		)
	}

	override suspend fun fileBrowserFolder(path: String): FileBrowserFolder = sanitizeExceptions {

		val user = call.authOrThrow()
		path.allowedOrThrow(user)

		// query the filesystem for one folder
		// NOTE: NFS filesystems can very VEEEERY slow!
		// so use an optimized folder listing function rather than the standard Java one
		var files = Filesystem.listFolderFast(path)
			?: throw ServiceException("File can't be opened as a folder")

		// post-process the files
		files = files
			.filter { it.name != "." && it.name != ".." }
			.sortedBy { it.name }

		// package the response to the client
		return FileBrowserFolder(
			entries = FileBrowserEntries(
				names = files.map { it.name },
				typeIds = files.map {
					when (it.type) {
						Filesystem.File.Type.Dir -> FileBrowserType.Folder
						Filesystem.File.Type.Reg -> FileBrowserType.File
						Filesystem.File.Type.Lnk -> FileBrowserType.Symlink
						else -> FileBrowserType.Other
					}.ordinal
				}
			)
		)
	}

	override suspend fun fileBrowserFile(path: String): FileBrowserFile = sanitizeExceptions {

		val user = call.authOrThrow()
		val p = path.allowedOrThrow(user)

		return when {

			!p.exists() -> throw ServiceException("file not found")

			p.isSymbolicLink() -> FileBrowserFile(
				type = FileBrowserType.Symlink,
				linkTarget = when {
					p.isDirectory() -> FileBrowserFile(FileBrowserType.Folder)
					p.isRegularFile() -> FileBrowserFile(
						FileBrowserType.File,
						size = p.size()
					)
					else -> FileBrowserFile(FileBrowserType.Other)
				}
			)

			p.isDirectory() -> FileBrowserFile(FileBrowserType.Folder)

			p.isRegularFile() -> FileBrowserFile(
				FileBrowserType.File,
				size = p.size()
			)

			else -> FileBrowserFile(FileBrowserType.Other)
		}
	}

	override suspend fun globPickerCount(path: String): GlobCount = sanitizeExceptions {

		val user = call.authOrThrow()
		val p = path.allowedOrThrow(user)

		return p.globCount().let {
			GlobCount(it.matched, it.total)
		}
	}


	private fun Path.size(): Long? =
		try {
			Files.size(this)
		} catch (t: Throwable) {
			null
		}

	private fun String.allowedOrThrow(user: User): Path {
		val p = Paths.get(this)
		p.allowedOrThrow(user)
		return p
	}

	private fun Path.allowedOrThrow(user: User) {

		fun bail(): Nothing =
			throw AuthenticationExceptionWithInternal("folder not allowed", "for user $user to path $this")

		// don't allow any relative paths like ../../../ etc
		if (contains(Paths.get(".."))) {
			bail()
		}

		// otherwise, the path must be in one of the allowed roots
		if (user.allowedRoots().none { startsWith(it) }) {
			bail()
		}
	}


	override suspend fun clusterQueues(): ClusterQueues = sanitizeExceptions {

		return Cluster.queues
	}

	override suspend fun users(search: String?, initial: String?, state: String?): List<RemoteOption> = sanitizeExceptions {

		call.authOrThrow()

		if (search == null) {
			return emptyList()
		}

		val query = search.lowercase()
		return Database.users.getAllUsers()
			.filter { user -> query in user.id.lowercase() || query in user.name.lowercase() }
			.map { user -> RemoteOption(user.id, user.name) }
	}
}
