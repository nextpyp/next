package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.div
import edu.duke.bartesaghi.micromon.pyp.ArgInput
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import io.kvision.remote.RemoteOption
import kotlinx.serialization.Serializable


@KVService
interface IFormService {

	@KVBindingRoute("form/fileBrowser/init")
	suspend fun fileBrowserInit(): FileBrowserInit

	@KVBindingRoute("form/fileBrowser/folder")
	suspend fun fileBrowserFolder(path: String): FileBrowserFolder

	@KVBindingRoute("form/fileBrowser/file")
	suspend fun fileBrowserFile(path: String): FileBrowserFile

	@KVBindingRoute("form/globPicker/count")
	suspend fun globPickerCount(path: String): GlobCount

	@KVBindingRoute("form/clusterQueues")
	suspend fun clusterQueues(): ClusterQueues

	@KVBindingRoute("form/users")
	suspend fun users(search: String?, initial: String?, state: String?): List<RemoteOption>
}


@Serializable
data class FileBrowserInit(
	val dataDirs: FileBrowserEntries,
	val homeDir: String,
	val projectsDir: String
	// TODO: links to other users' shared files?
)

@Serializable
data class FileBrowserFolder(
	val entries: FileBrowserEntries
)

@Serializable
data class FileBrowserFile(
	val type: FileBrowserType,
	val linkTarget: FileBrowserFile? = null,
	val size: Long? = null
)

@Serializable
data class GlobCount(
	val matched: Int,
	val total: Int
)


/**
 * Performance here is actually important, so prefer struct-of-arrays
 * over array-of-structs, to speed up json serialization/deserialization
 */
@Serializable
data class FileBrowserEntries(
	val names: List<String>,
	val typeIds: List<Int>
) {
	init {
		if (names.size != typeIds.size) {
			throw IllegalArgumentException("wrong sizes")
		}
	}

	val size: Int get() = names.size

	fun type(i: Int): FileBrowserType =
		FileBrowserType.values()[typeIds[i]]
}

enum class FileBrowserType {
	Folder,
	File,
	Symlink,
	Other
}


enum class PathType(val id: String) {

	Project("project");

	private val prefix: String get() =
		"$id:/"

	fun make(path: String): String =
		prefix + path

	fun matches(path: String): Boolean =
		path.startsWith(prefix)

	fun absolutize(folder: String, path: String): String {

		// strip the type from the path
		val relPath = path.substring(prefix.length)

		return folder / relPath
	}
}


@Serializable
data class ClusterQueues(
	val cpu: List<String>,
	val gpu: List<String>
) {

	operator fun get(group: ArgInput.ClusterQueue.Group): List<String> =
		when (group) {
			ArgInput.ClusterQueue.Group.Cpu -> cpu
			ArgInput.ClusterQueue.Group.Gpu -> gpu
		}
}
