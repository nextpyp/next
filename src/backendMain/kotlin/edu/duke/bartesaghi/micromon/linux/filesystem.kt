package edu.duke.bartesaghi.micromon.linux

import com.sun.jna.*
import edu.duke.bartesaghi.micromon.slowIOs
import java.nio.file.Path
import kotlin.io.path.pathString


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

		// https://man7.org/linux/man-pages/man2/stat.2.html
		// NOTE: Some libc builds don't export the stat symbol directly.
		//       Sometimes stats is some internal function or macro that calls __xstat64.
		//       So we'll have to call the symbol that's actually exported directly here
		//       See: https://refspecs.linuxbase.org/LSB_5.0.0/LSB-Core-generic/LSB-Core-generic/baselib---xstat64.html
		//external fun stat(path: String, stat: Stat.ByRef): Int
		external fun __xstat64(ver: Int, path: String, stat: Stat.ByRef): Int

		// https://man7.org/linux/man-pages/man3/getpwuid.3p.html
		external fun getpwuid(uid: Int): Passwd.ByRef?

		// https://man7.org/linux/man-pages/man3/getgrgid.3p.html
		external fun getgrgid(gid: Int): Group.ByRef?

		// https://man7.org/linux/man-pages/man3/group_member.3.html
		external fun group_member(gid: Int): Int

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

		// https://man7.org/linux/man-pages/man3/stat.3type.html
		@Structure.FieldOrder(
			"st_dev", "st_ino", "st_nlink", "st_mode", "st_uid", "st_gid", "st_rdev", "st_size", "st_blksize",
			"st_blocks", "st_atim", "st_mtim", "st_ctim"
		)
		open class Stat(
			@JvmField var st_dev: NativeLong = NativeLong(), /* ID of device containing file */
			@JvmField var st_ino: NativeLong = NativeLong(), /* Inode number */
			@JvmField var st_mode: Int = 0, /* File type and mode */
			@JvmField var st_nlink: NativeLong = NativeLong(), /* Number of hard links */
			@JvmField var st_uid: Int = 0, /* User ID of owner */
			@JvmField var st_gid: Int = 0, /* Group ID of owner */
			@JvmField var st_rdev: NativeLong = NativeLong(), /* Device ID (if special file) */
			@JvmField var st_size: NativeLong = NativeLong(), /* Total size, in bytes */
			@JvmField var st_blksize: NativeLong = NativeLong(), /* Block size for filesystem I/O */
			@JvmField var st_blocks: NativeLong = NativeLong(), /* Number of 512B blocks allocated */
			@JvmField var st_atim: Timespec = Timespec(), /* Time of last access */
			@JvmField var st_mtim: Timespec = Timespec(), /* Time of last modification */
			@JvmField var st_ctim: Timespec = Timespec(), /* Time of last status change */
		) : Structure() {
			class ByRef : Stat(), ByReference
		}

		@Structure.FieldOrder(
			"tv_sec", "tv_nsec"
		)
		open class Timespec(
			@JvmField var tv_sec: Long = 0,
			@JvmField var tv_nsec: Long = 0
		) : Structure()

		// https://man7.org/linux/man-pages/man0/pwd.h.0p.html
		@Structure.FieldOrder(
			"pw_name", "pw_uid", "pw_gid", "pw_dir", "pw_shell"
		)
		open class Passwd(
			@JvmField var pw_name: Pointer? = null,
			@JvmField var pw_uid: Int = 0,
			@JvmField var pw_gid: Int = 0,
			@JvmField var pw_dir: Pointer? = null,
			@JvmField var pw_shell: Pointer? = null
		) : Structure() {
			class ByRef : Passwd(), ByReference
		}

		// https://man7.org/linux/man-pages/man0/grp.h.0p.html
		@Structure.FieldOrder(
			"gr_name", "gr_gid", "gr_mem"
		)
		open class Group(
			@JvmField var gr_name: Pointer? = null,
			@JvmField var gr_gid: Int = 0,
			@JvmField var gr_mem: Pointer? = null,
		) : Structure() {
			class ByRef : Group(), ByReference
		}

		init {
			Native.register(javaClass, Platform.C_LIBRARY_NAME)
		}
	}


	class NativeException(
		val err: Int = Native.getLastError(),
		val errMsg: String = native.strerror(Native.getLastError())
	) : RuntimeException("native function failed: $err: $errMsg") {

		companion object {

			fun exists(): Boolean =
				Native.getLastError() != 0
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
					-1L -> throw NativeException()
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

	/**
	 * Run a real linux stat(), since the Java stdlib wrappers hide some important details,
	 * like setuid and setgid
	 */
	suspend fun stat(path: Path): Stat = slowIOs {

		val stat = native.Stat.ByRef()
		val ret = native.__xstat64(1, path.pathString, stat)
		// NOTE: the stat version should always be 1 on 64-bit platforms
		if (ret != 0) {
			throw NativeException()
		}

		Stat(
			mode = stat.st_mode,
			uid = stat.st_uid,
			gid = stat.st_gid
		)
	}

	data class Stat(
		val mode: Int,
		val uid: Int,
		val gid: Int
	) {

		// https://man7.org/linux/man-pages/man7/inode.7.html

		val isSetUID: Boolean get() =
			(mode and (1 shl 11)) != 0

		val isSetGID: Boolean get() =
			(mode and (1 shl 10)) != 0

		val isSticky: Boolean get() =
			(mode and (1 shl 9)) != 0

		val isOwnerRead: Boolean get() =
			(mode and (1 shl 8)) != 0

		val isOwnerWrite: Boolean get() =
			(mode and (1 shl 7)) != 0

		val isOwnerExecute: Boolean get() =
			(mode and (1 shl 6)) != 0

		val isGroupRead: Boolean get() =
			(mode and (1 shl 5)) != 0

		val isGroupWrite: Boolean get() =
			(mode and (1 shl 4)) != 0

		val isGroupExecute: Boolean get() =
			(mode and (1 shl 3)) != 0

		val isOtherRead: Boolean get() =
			(mode and (1 shl 2)) != 0

		val isOtherWrite: Boolean get() =
			(mode and (1 shl 1)) != 0

		val isOtherExecute: Boolean get() =
			(mode and (1 shl 0)) != 0

		suspend fun username(): String? =
			getUsername(uid)

		suspend fun groupname(): String? =
			getGroupname(gid)
	}

	suspend fun getUsername(uid: Int): String? = slowIOs {

		val passwd = native.getpwuid(uid)
		if (NativeException.exists()) {
			throw NativeException()
		}

		passwd?.pw_name?.getString(0)
	}

	suspend fun getGroupname(gid: Int): String? = slowIOs {

		val group = native.getgrgid(gid)
		if (NativeException.exists()) {
			throw NativeException()
		}

		group?.gr_name?.getString(0)
	}

	suspend fun groupMember(gid: Int): Boolean = slowIOs {
		native.group_member(gid) != 0
	}
}


/* DEBUG
fun main() {
	val path = "/"
	println(Filesystem.listFolderFast(path)?.map { it.name })
}
*/
