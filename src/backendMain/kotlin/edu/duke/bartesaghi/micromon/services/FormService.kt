package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.allowedPathOrThrow
import edu.duke.bartesaghi.micromon.auth.allowedRoots
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.auth.dir
import edu.duke.bartesaghi.micromon.cluster.slurm.TemplateEngine
import edu.duke.bartesaghi.micromon.linux.Filesystem
import edu.duke.bartesaghi.micromon.linux.userprocessor.Response.Stat
import edu.duke.bartesaghi.micromon.linux.userprocessor.globCountAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.listFolderFastAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.statAs
import edu.duke.bartesaghi.micromon.mongo.Database
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
import java.nio.file.Paths


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


	override suspend fun fileBrowserInit(): FileBrowserInit = sanitizeExceptions {

		val user = call.authOrThrow()

		val paths = user.allowedRoots()

		return FileBrowserInit(
			dataDirs = FileBrowserEntries(
				names = paths.map { it.toString() },
				typeIds = paths.map { FileBrowserType.Folder.ordinal }
			),
			userDir = User.dir(user.id, null).toString(),
			osUserDir = user.osUsername?.let { User.dir(user.id, it).toString() }
		)
	}

	override suspend fun fileBrowserFolder(path: String): FileBrowserFolder = sanitizeExceptions {

		val user = call.authOrThrow()
		path.allowedPathOrThrow(user)

		// query the filesystem for one folder
		// NOTE: NFS filesystems can very VEEEERY slow!
		// so use an optimized folder listing function rather than the standard Java one
		var files = Paths.get(path).listFolderFastAs(user.osUsername)
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
		val p = path.allowedPathOrThrow(user)

		return when (val stat = p.statAs(user.osUsername)) {
			Stat.NotFound -> throw ServiceException("file not found")
			is Stat.File -> FileBrowserFile(
				type = FileBrowserType.File,
				size = stat.size.toLong()
			)
			Stat.Dir -> FileBrowserFile(FileBrowserType.Folder)
			is Stat.Symlink -> FileBrowserFile(
				type = FileBrowserType.Symlink,
				linkTarget = when (val t = stat.response) {
					Stat.Symlink.NotFound -> FileBrowserFile(FileBrowserType.BrokenSymlink)
					is Stat.Symlink.File -> FileBrowserFile(
						type = FileBrowserType.File,
						size = t.size.toLong()
					)
					Stat.Symlink.Dir -> FileBrowserFile(FileBrowserType.Folder)
					Stat.Symlink.Other -> FileBrowserFile(FileBrowserType.Other)
				}
			)
			Stat.Other -> FileBrowserFile(FileBrowserType.Other)
		}
	}

	override suspend fun globPickerCount(path: String): GlobCount = sanitizeExceptions {

		val user = call.authOrThrow()
		val p = path.allowedPathOrThrow(user)

		return p.globCountAs(user.osUsername).let {
			GlobCount(it.matched, it.total)
		}
	}


	override suspend fun clusterTemplates(): List<TemplateData> = sanitizeExceptions {

		call.authOrThrow()

		val config = Config.instance.slurm
			?: return emptyList()

		return TemplateEngine.findTemplates(config)
			.mapNotNull { template ->
				try {
					template.readData()
				} catch (t: Throwable) {
					Backend.log.error("Failed to read template front matter from: ${template.absPath}", t)
					null
				}
			}
	}

	override suspend fun users(search: String?, initial: String?, state: String?): List<RemoteOption> = sanitizeExceptions {

		call.authOrThrow()

		if (search == null) {
			return emptyList()
		}

		val query = search.lowercase()
		return Database.instance.users.getAllUsers()
			.filter { user -> query in user.id.lowercase() || query in user.name.lowercase() }
			.map { user -> RemoteOption(user.id, user.name) }
	}
}
