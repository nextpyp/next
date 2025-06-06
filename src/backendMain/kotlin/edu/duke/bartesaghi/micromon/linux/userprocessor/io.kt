package edu.duke.bartesaghi.micromon.linux.userprocessor

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.linux.Filesystem
import edu.duke.bartesaghi.micromon.linux.userprocessor.Response.Stat
import edu.duke.bartesaghi.micromon.services.GlobCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.HashSet
import kotlin.io.path.*
import kotlin.io.path.readBytes


suspend fun Path.writeBytesAs(username: String?, content: ByteArray) {
	if (username != null) {
		Backend.instance.userProcessors.get(username)
			.writeFile(this).use { writer ->
				writer.writeAll(content)
			}
	} else {
		slowIOs {
			writeBytes(content)
		}
	}
}

suspend fun Path.writeStringAs(username: String?, content: String) =
	writeBytesAs(username, content.toByteArray(Charsets.UTF_8))


suspend fun Path.readBytesAs(username: String?): ByteArray =
	if (username != null) {
		Backend.instance.userProcessors.get(username)
			.readFile(this)
			.use { it.readAll() }
	} else {
		slowIOs {
			readBytes()
		}
	}

suspend fun Path.readStringAs(username: String?): String =
	readBytesAs(username).toString(Charsets.UTF_8)


suspend fun Path.editPermissionsAs(username: String?, editor: PosixPermissionsEditor.() -> Unit) {
	if (username != null) {

		val ops = ArrayList<Request.Chmod.Op>()
		var adds: MutableSet<Request.Chmod.Bit>? = null
		var dels: MutableSet<Request.Chmod.Bit>? = null

		fun makeOp(value: Boolean, bits: Set<Request.Chmod.Bit>) {
			ops.add(Request.Chmod.Op(
				value,
				bits = bits.toList()
			))
		}

		fun shipAdds() {
			adds?.let { makeOp(true, it) }
			adds = null
		}

		fun shipDels() {
			dels?.let { makeOp(false, it) }
			dels = null
		}

		fun PosixFilePermission.toBit(): Request.Chmod.Bit =
			when (this) {
				PosixFilePermission.OWNER_READ -> Request.Chmod.Bit.UserRead
				PosixFilePermission.OWNER_WRITE -> Request.Chmod.Bit.UserWrite
				PosixFilePermission.OWNER_EXECUTE -> Request.Chmod.Bit.UserExecute
				PosixFilePermission.GROUP_READ -> Request.Chmod.Bit.GroupRead
				PosixFilePermission.GROUP_WRITE -> Request.Chmod.Bit.GroupWrite
				PosixFilePermission.GROUP_EXECUTE -> Request.Chmod.Bit.GroupExecute
				PosixFilePermission.OTHERS_READ -> Request.Chmod.Bit.OtherRead
				PosixFilePermission.OTHERS_WRITE -> Request.Chmod.Bit.OtherWrite
				PosixFilePermission.OTHERS_EXECUTE -> Request.Chmod.Bit.OtherExecute
				// NOTE: the JRE POSIX file permissions API doesn't represent SetUid, SetGid, or Sticky bits
			}

		object : PosixPermissionsEditor {

			override fun add(perm: PosixFilePermission) {
				shipDels()
				(adds ?: HashSet<Request.Chmod.Bit>().also { adds = it })
					.add(perm.toBit())
			}

			override fun del(perm: PosixFilePermission) {
				shipAdds()
				(dels ?: HashSet<Request.Chmod.Bit>().also { dels = it })
					.add(perm.toBit())
			}

		}.editor()

		shipAdds()
		shipDels()

		Backend.instance.userProcessors.get(username)
			.chmod(this, ops)
	} else {
		slowIOs {
			editPermissions(editor)
		}
	}
}


suspend fun Path.deleteAs(username: String?) {
	if (username != null) {
		Backend.instance.userProcessors.get(username)
			.deleteFile(this)
	} else {
		slowIOs {
			delete()
		}
	}
}


suspend fun Path.createDirsIfNeededAs(username: String?): Path {
	if (username != null) {
		Backend.instance.userProcessors.get(username)
			.createFolder(this)
	} else {
		slowIOs {
			createDirsIfNeeded()
		}
	}
	return this
}


suspend fun Path.deleteDirRecursivelyAs(username: String?) {
	if (username != null) {
		Backend.instance.userProcessors.get(username)
			.deleteFolder(this)
	} else {
		slowIOs {
			deleteDirRecursively()
		}
	}
}


fun Path.deleteDirRecursivelyAsyncAs(username: String?) {
	if (username != null) {
		Backend.instance.scope.launch(Dispatchers.IO) {
			deleteDirRecursivelyAs(username)
		}
	} else {
		deleteDirRecursivelyAsync()
	}
}


suspend fun Path.copyDirRecursivelyAs(username: String?, dst: Path) {
	if (username != null) {
		Backend.instance.userProcessors.get(username)
			.copyFolder(this, dst)
	} else {
		copyDirRecursivelyTo(dst)
	}
}


fun FileEntry.toFile(): Filesystem.File =
	Filesystem.File(
		name = name,
		type = when (kind) {
			FileEntry.Kind.File -> Filesystem.File.Type.Reg
			FileEntry.Kind.Dir -> Filesystem.File.Type.Dir
			FileEntry.Kind.Symlink -> Filesystem.File.Type.Lnk
			FileEntry.Kind.Fifo -> Filesystem.File.Type.Fifo
			FileEntry.Kind.Socket -> Filesystem.File.Type.Sock
			FileEntry.Kind.BlockDev -> Filesystem.File.Type.Blk
			FileEntry.Kind.CharDev -> Filesystem.File.Type.Chr
			else -> Filesystem.File.Type.Unknown
		}
	)

suspend fun Path.listFolderFastAs(username: String?): List<Filesystem.File>? =
	if (username != null) {
		Backend.instance.userProcessors.get(username)
			.listFolder(this)
			.map { it.toFile() }
			.toList()
	} else {
		slowIOs {
			listFolderFast()
		}
	}


suspend fun Path.statAs(username: String?): Stat.Response =
	if (username != null) {
		Backend.instance.userProcessors.get(username)
			.stat(this)
	} else {
		slowIOs {
			stat()
		}
	}


suspend fun Path.renameAs(username: String?, name: String) {
	if (username != null) {
		Backend.instance.userProcessors.get(username)
			.rename(this, parent / name)
	} else {
		slowIOs {
			rename(name)
		}
	}
}


suspend fun Path.symlinkAs(username: String?, link: Path) {
	if (username != null) {
		Backend.instance.userProcessors.get(username)
			.symlink(this, link)
	} else {
		slowIOs {
			symlink(link)
		}
	}
}


suspend fun Path.globCountAs(username: String?): GlobCount =
	if (username != null) {
		Backend.instance.userProcessors.get(username)
			.listFolder(parent)
			.map { it.toFile() }
			.toList()
	} else {
		Filesystem.listFolderFast(parent.toString())
			?: emptyList()
	}.globCount(fileName.toString())


suspend fun Path.readerAs(username: String?): UserProcessor.FileReader =
	if (username != null) {
		Backend.instance.userProcessors.get(username)
			.readFile(this)
	} else {
		object : UserProcessor.FileReader {

			private val reader = this@readerAs.inputStream()

			override val size = fileSize().toULong()

			override suspend fun read(): ByteArray? =
				slowIOs {
					val buf = ByteArray(32*1024)
					val bytesRead = reader.read(buf)
					if (bytesRead <= 0) {
						null
					} else if (bytesRead < buf.size) {
						buf.copyOfRange(0, bytesRead)
					} else {
						buf
					}
				}

			override suspend fun closeAll() {
				slowIOs {
					reader.close()
				}
			}
		}
	}


suspend fun Path.writerAs(username: String?, append: Boolean = false): UserProcessor.FileWriter =
	if (username != null) {
		Backend.instance.userProcessors.get(username)
			.writeFile(this, append)
	} else {
		object : UserProcessor.FileWriter {

			private val writer = this@writerAs.outputStream(
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE,
				if (append) {
					StandardOpenOption.TRUNCATE_EXISTING
				} else {
					StandardOpenOption.APPEND
				}
			)

			override suspend fun write(chunk: ByteArray) {
				slowIOs {
					writer.write(chunk)
				}
			}

			override suspend fun closeAll() {
				slowIOs {
					writer.close()
				}
			}
		}
	}


class WebCacheDir(val path: Path, val osUsername: String?) {

	fun exists(): Boolean =
		path.exists()

	suspend fun createIfNeeded() {
		if (!exists()) {
			create()
		}
	}

	suspend fun create() {
		path.createDirsIfNeededAs(osUsername)
		path.editPermissionsAs(osUsername) {
			add(PosixFilePermission.GROUP_READ)
			add(PosixFilePermission.GROUP_WRITE)
			add(PosixFilePermission.GROUP_EXECUTE)
		}
	}

	suspend fun recreate() {
		if (exists()) {
			path.deleteDirRecursivelyAs(osUsername)
		}
		create()
	}


	@JvmInline
	value class Key private constructor(val id: String) {

		companion object {

			private val ids = HashSet<String>()

			fun reserve(id: String): Key {

				// make sure the key is unique
				if (id in ids) {
					throw IllegalArgumentException("web cache key used multiple times: $id")
				}
				ids.add(id)

				return Key(id)
			}
		}

		fun parameterized(param: String): Key =
			Key("$id-$param")
	}


	object Keys {

		val fyp = Key.reserve("fyp")
		val map = Key.reserve("map")
		val particles = Key.reserve("particles")
		val gainCorrected = Key.reserve("gain-corrected")
		val datum = Key.reserve("datum")
		val ctffit = Key.reserve("ctffit")
		val output = Key.reserve("output")
		val twodClasses = Key.reserve("two2classes")
		val miloResults2D = Key.reserve("milo-results-2d")
		val miloLabels2D = Key.reserve("milo-results-2d-labels")
		val miloResults3D = Key.reserve("milo-results-3d")
		val tomoDrgnVolume = Key.reserve("tomodrgn-vol")
	}
}
