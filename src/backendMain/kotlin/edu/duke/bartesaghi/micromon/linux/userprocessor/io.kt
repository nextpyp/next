package edu.duke.bartesaghi.micromon.linux.userprocessor

import edu.duke.bartesaghi.micromon.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.HashSet
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes


suspend fun Path.writeBytesAs(username: String?, content: ByteArray) {
	if (username != null) {
		Backend.userProcessors.get(username)
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
		Backend.userProcessors.get(username)
			.readFile(this)
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

		Backend.userProcessors.get(username)
			.chmod(this, ops)
	} else {
		slowIOs {
			editPermissions(editor)
		}
	}
}


suspend fun Path.deleteAs(username: String?) {
	if (username != null) {
		Backend.userProcessors.get(username)
			.deleteFile(this)
	} else {
		slowIOs {
			delete()
		}
	}
}


suspend fun Path.createDirsIfNeededAs(username: String?): Path {
	if (username != null) {
		Backend.userProcessors.get(username)
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
		Backend.userProcessors.get(username)
			.deleteFolder(this)
	} else {
		slowIOs {
			deleteDirRecursively()
		}
	}
}


fun Path.deleteDirRecursivelyAsyncAs(username: String?) {
	if (username != null) {
		Backend.scope.launch(Dispatchers.IO) {
			deleteDirRecursivelyAs(username)
		}
	} else {
		deleteDirRecursivelyAsync()
	}
}


suspend fun Path.writerAs(username: String?, append: Boolean = false): UserProcessor.FileWriter =
	if (username != null) {
		Backend.userProcessors.get(username)
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


class WebCacheDir(val path: Path) {

	fun exists(): Boolean =
		path.exists()

	suspend fun createIfNeeded(username: String?): Path {
		if (!exists()) {
			create(username)
		}
		return path
	}

	suspend fun create(username: String?) {
		path.createDirsIfNeededAs(username)
		path.editPermissionsAs(username) {
			add(PosixFilePermission.GROUP_READ)
			add(PosixFilePermission.GROUP_WRITE)
			add(PosixFilePermission.GROUP_EXECUTE)
		}
	}

	suspend fun recreate(username: String?) {
		if (exists()) {
			path.deleteDirRecursivelyAs(username)
		}
		if (!exists()) {
			create(username)
		}
	}
}
