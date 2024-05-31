package edu.duke.bartesaghi.micromon.linux.userprocessor

import edu.duke.bartesaghi.micromon.*
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.HashSet
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
