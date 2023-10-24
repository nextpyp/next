package edu.duke.bartesaghi.micromon.linux

import com.sun.jna.*
import edu.duke.bartesaghi.micromon.slowIOs
import kotlin.collections.ArrayList


/**
 * Java's file API can be very slow, especially on remote-mounted filesystems with large numbers of files.
 *
 * This object provides the most efficient API possible for querying basic filesystem information,
 * by directly calling functions from GNU libc.
 *
 * Naturally, this means it only works on Linux.
 */
object Filesystem {

	internal object native {

		external fun opendir(name: String): DIR?
		external fun readdir(dir: DIR): Dirent?
		external fun closedir(dir: DIR): Int

		// https://man7.org/linux/man-pages/man2/getdents.2.html
		//external fun getdents64(fd: Int, dirp: Pointer, count: Long): Long
		// apparently not in older versions of libc

		// NOTE: I don't know how to get the varargs working with Kotlin and JNA,
		// so just use a signature of syscall() here that's specialized to getdents64
		// https://man7.org/linux/man-pages/man2/syscall.2.html
		external fun syscall(number: Long, fd: Int, dirp: Pointer, count: Long): Long

		// https://www.man7.org/linux/man-pages/man2/open.2.html
		external fun open(name: String, flags: Int): Int

		// https://www.man7.org/linux/man-pages/man2/close.2.html
		external fun close(fd: Int): Int

		// https://man7.org/linux/man-pages/man3/strerror.3.html
		external fun strerror(errnum: Int): String


		const val DT_UNKNOWN = 0
		const val DT_FIFO = 1
		const val DT_CHR = 2
		const val DT_DIR = 4 // folder
		const val DT_BLK = 6
		const val DT_REG = 8 // regular file
		const val DT_LNK = 10 // symlink
		const val DT_SOCK = 12
		const val DT_WHT = 14

		const val O_RDONLY = 0x00000000
		const val O_DIRECTORY = 0x00010000

		const val SYS_getdents64 = 217L // on 64-bit Linux


		class DIR : PointerType()

		@Structure.FieldOrder(
			"d_ino", "d_off",
			"d_reclen", "d_type", "d_name"
		)
		open class Dirent(
			/** inode id */
			@JvmField var d_ino: Long = 0,
			@JvmField var d_off: Long = 0,
			/** length of inode record, not the filesize */
			@JvmField var d_reclen: Short = 0,
			@JvmField var d_type: Byte = 0,
			@JvmField var d_name: ByteArray = ByteArray(1)
		) : Structure() {

			fun name(p: Pointer): String {
				return p.getString(fieldOffset("d_name").toLong())
			}

			fun readFrom(p: Pointer) {
				useMemory(p)
				read()
			}
		}

		init {
			Native.register(javaClass, Platform.C_LIBRARY_NAME)
		}
	}


	data class File(
		val name: String,
		val type: Type
	) {
		enum class Type(val id: Int) {

			Unknown(native.DT_UNKNOWN),

			/** named pipe */
			Fifo(native.DT_FIFO),

			/** character device */
			Chr(native.DT_CHR),

			/** folder */
			Dir(native.DT_DIR),

			/** block device */
			Blk(native.DT_BLK),

			/** regular file */
			Reg(native.DT_REG),

			/** symbolic link */
			Lnk(native.DT_LNK),

			/** Unix domain socket */
			Sock(native.DT_SOCK),

			/** BSD whiteout */
			Wht(native.DT_WHT);

			companion object {

				val values = Array<Type?>(16) { null }
					.apply {
						for (v in values()) {
							this[v.id] = v
						}
					}

				fun from(b: Byte): Type =
					values[b.toInt()] ?: Unknown
			}
		}
	}

	/**
	 * Returns the file listing of the folder, or null if the folder can't be opened
	 * Uses raw kernel syscalls, should be fastest possible interface to the filesystem
	 */
	suspend fun listFolderFast(path: String): List<File>? = slowIOs f@{

		// allocate lots of space for directory entries
		val buf = Memory(1024*1024)

		// open the directory for reading
		val fd = native.open(path, native.O_RDONLY or native.O_DIRECTORY)
		if (fd == -1) {
			return@f null
		}

		try {

			// read the files
			val files = ArrayList<File>()
			while (true) {
				when (val bytesRead = native.syscall(native.SYS_getdents64, fd, buf, buf.size())) {
					-1L -> throw Error("getdents64() failed: ${Native.getLastError()}: ${native.strerror(Native.getLastError())}")
					0L -> break // end of buffer
					else -> {

						var p = 0L
						while (p < bytesRead) {

							// read the directory entry from the buffer
							val dirent = native.Dirent()
							val ptr = buf.share(p)
							dirent.readFrom(ptr)

							// just in case ...
							if (dirent.d_reclen <= 0.toShort()) {
								throw Error("dirent read fail")
							}

							files.add(File(
								name = dirent.name(ptr),
								type = File.Type.from(dirent.d_type)
							))

							p += dirent.d_reclen
						}
					}
				}
			}

			files

		} finally {
			native.close(fd)
		}
	}
}


/* DEBUG
fun main() {
	val path = "/"
	println(Filesystem.listFolderFast(path)?.map { it.name })
}
*/
