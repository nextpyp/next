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

		// https://man7.org/linux/man-pages/man3/getuid.3p.html
		external fun getuid(): Int

		// https://man7.org/linux/man-pages/man3/geteuid.3p.html
		external fun geteuid(): Int

		/* NOTE:
			User and group query functions are nearly useless inside the container,
			since the container won't generally know about users and groups on the host OS.
			Just mapping PAM config (/etc/pam.d/ and files like /etc/passwd, /etc/group) may work in some cases,
			but won't work in general, because PAM configuration may rely on userspace libraries
			that aren't in the container.
			Also, the container may use a different user namespace than the host OS,
			although apptainer doesn't generally do that unless you use --fakeroot, which we don't.

			So don't use these user,group query functions from the local kernel.
			Instead, talk to the host processor to get info from the host.

			Tragically, we're one syscall away from accessing host data from inside the container.
			setns() would let us move to the host mnt namespace (apptainer only seems to use mnt namespaces by default)
			which would let user,group queries return useful results, but alas, moving into a mnt namespace
			requires root permisisons. They haven't made a root-less way to do this yet, even though
			creating the mnt namespace and moving into it didn't require root to begin with.
			The lack of symmetry here is annoying! >8(
			See: https://www.man7.org/linux/man-pages/man2/setns.2.html

		// https://man7.org/linux/man-pages/man3/getpwuid.3p.html
		external fun getpwuid(uid: Int): Passwd.ByRef?

		// https://man7.org/linux/man-pages/man3/getgrgid.3p.html
		external fun getgrgid(gid: Int): Group.ByRef?

		// https://man7.org/linux/man-pages/man3/group_member.3.html
		external fun group_member(gid: Int): Int

		*/

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
		// but actually, see: /usr/include/bits/struct_stat.h
		@Structure.FieldOrder(
			"st_dev", "st_ino", "st_nlink",
			"st_mode", "st_uid", "st_gid", "__pad0",
			"st_rdev", "st_size", "st_blksize", "st_blocks",
			"st_atim", "st_mtim", "st_ctim",
			"__glibc_reserved"
		)
		open class Stat(
			@JvmField var st_dev: Long = 0L, /* ID of device containing file */
			@JvmField var st_ino: Long = 0L, /* Inode number */
			@JvmField var st_nlink: Long = 0L, /* Number of hard links */
			@JvmField var st_mode: Int = 0, /* File type and mode */
			@JvmField var st_uid: Int = 0, /* User ID of owner */
			@JvmField var st_gid: Int = 0, /* Group ID of owner */
			@JvmField var __pad0: Int = 0, /* for 8-byte word alignment */
			@JvmField var st_rdev: Long = 0L, /* Device ID (if special file) */
			@JvmField var st_size: Long = 0L, /* Total size, in bytes */
			@JvmField var st_blksize: Long = 0L, /* Block size for filesystem I/O */
			@JvmField var st_blocks: Long = 0L, /* Number of 512B blocks allocated */
			@JvmField var st_atim: Timespec = Timespec(), /* Time of last access */
			@JvmField var st_mtim: Timespec = Timespec(), /* Time of last modification */
			@JvmField var st_ctim: Timespec = Timespec(), /* Time of last status change */
			@JvmField var __glibc_reserved: ByteArray = ByteArray(24),
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
			mode = stat.st_mode.toUInt(),
			uid = stat.st_uid.toUInt(),
			gid = stat.st_gid.toUInt()
		)
	}

	data class Stat(
		val mode: UInt,
		val uid: UInt,
		val gid: UInt
	) {

		// https://man7.org/linux/man-pages/man7/inode.7.html

		val isSetUID: Boolean get() =
			(mode and (1u shl 11)) != 0u

		val isSetGID: Boolean get() =
			(mode and (1u shl 10)) != 0u

		val isSticky: Boolean get() =
			(mode and (1u shl 9)) != 0u

		val isOwnerRead: Boolean get() =
			(mode and (1u shl 8)) != 0u

		val isOwnerWrite: Boolean get() =
			(mode and (1u shl 7)) != 0u

		val isOwnerExecute: Boolean get() =
			(mode and (1u shl 6)) != 0u

		val isGroupRead: Boolean get() =
			(mode and (1u shl 5)) != 0u

		val isGroupWrite: Boolean get() =
			(mode and (1u shl 4)) != 0u

		val isGroupExecute: Boolean get() =
			(mode and (1u shl 3)) != 0u

		val isOtherRead: Boolean get() =
			(mode and (1u shl 2)) != 0u

		val isOtherWrite: Boolean get() =
			(mode and (1u shl 1)) != 0u

		val isOtherExecute: Boolean get() =
			(mode and (1u shl 0)) != 0u
	}

	suspend fun getUid(): UInt = slowIOs {
		native.getuid().toUInt()
	}

	suspend fun getEUid(): UInt = slowIOs {
		native.geteuid().toUInt()
	}
}


/* DEBUG
fun main() {

	val path = "/"
	println(Filesystem.listFolderFast(path)?.map { it.name })

	kotlinx.coroutines.runBlocking {
		val stat = Filesystem.stat(java.nio.file.Paths.get("/home/jeff/archbtw"))
		println("stat: $stat")
		println("UID: ${Filesystem.getUid()}")
	}
}
*/
