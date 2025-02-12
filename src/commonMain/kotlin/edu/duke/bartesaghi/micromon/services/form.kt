package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.div
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

	@KVBindingRoute("form/clusterTemplates")
	suspend fun clusterTemplates(): List<TemplateData>

	@KVBindingRoute("form/users")
	suspend fun users(search: String?, initial: String?, state: String?): List<RemoteOption>
}


@Serializable
data class FileBrowserInit(
	val dataDirs: FileBrowserEntries,
	val userDir: String,
	val osUserDir: String?
	// TODO: links to other users' shared files?
) {

	fun homeDir(): String =
		osUserDir ?: userDir

	fun projectsDir(): String =
		homeDir() / "projects"
}

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
	BrokenSymlink,
	Other
}


@Serializable
data class TemplateData(
	val path: String,
	val title: String,
	val description: String?
)
