package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.createDirsIfNeeded
import edu.duke.bartesaghi.micromon.delete
import edu.duke.bartesaghi.micromon.listFiles
import kotlinx.coroutines.runBlocking
import org.apache.commons.vfs2.FileChangeEvent
import org.apache.commons.vfs2.FileListener
import org.apache.commons.vfs2.FileObject
import org.apache.commons.vfs2.VFS
import org.apache.commons.vfs2.impl.DefaultFileMonitor
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*


/**
 * A filesystem watcher that uses explicit polling, rather than rely on kernel events (eg inotify),
 * since that's the only way I've found so far to get accurate events on remotely-mounted filesystems like NFS.
 *
 * This implementation uses the Apache VFS module, since it implements explicit polling.
 * The built-in Java NIO watcher does implement explicit polling, but tragically there seems to be
 * no way to enable it on Linux, where Java NIO seems to use inotify exclusively.
 *
 * https://commons.apache.org/proper/commons-vfs/commons-vfs2/
 */
class FilesystemWatcher(val dir: Path) {

	var onCreated: (suspend (path: Path) -> Unit)? = null
	var onDeleted: (suspend (path: Path) -> Unit)? = null
	var onChanged: (suspend (path: Path) -> Unit)? = null

	private val monitor = DefaultFileMonitor(object : FileListener {

		/**
		 * The usual FileObject.getPath() function tries to run the path through a URL
		 * before creating a Path instance, but tragically, not all valid Linux paths are
		 * also valid URLs. So this `safePath` property creates the Path object safely,
		 * ie without requiring an illegal translation through a URL.
		 */
		val FileObject.safePath: Path get() =
			Paths.get(name.path)

		override fun fileCreated(event: FileChangeEvent?) {
			event ?: return
			runBlocking {
				onCreated?.invoke(event.fileObject.safePath)
			}
		}

		override fun fileDeleted(event: FileChangeEvent?) {
			event ?: return
			runBlocking {
				onDeleted?.invoke(event.fileObject.safePath)
			}
		}

		override fun fileChanged(event: FileChangeEvent?) {
			event ?: return
			runBlocking {
				onChanged?.invoke(event.fileObject.safePath)
			}
		}

	}).apply {
		isRecursive = true
		addFile(VFS.getManager().resolveFile(dir.toString()))
		start()
	}

	fun stopAndWait() {
		monitor.stop()
	}
}


class TransferWatcher {

	var onWaiting: (suspend (path: Path) -> Unit)? = null
	var onStarted: (suspend (path: Path, bytesTotal: Long) -> Unit)? = null
	var onProgress: (suspend (path: Path, bytesTransferred: Long) -> Unit)? = null
	var onFinished: (suspend (path: Path) -> Unit)? = null

	private var srcDir: Path? = null
	private var dstDir: Path? = null
	private val srcFilenames = HashSet<Path>()
	private var srcWatcher: FilesystemWatcher? = null
	private var dstWatcher: FilesystemWatcher? = null

	data class FileInfo(
		val path: Path,
		val bytesTotal: Long,
		val bytesTransfered: Long
	)

	val isWatching: Boolean get() =
		srcWatcher != null && dstWatcher != null

	fun watch(srcDir: Path?, dstDir: Path?): List<FileInfo> {

		this.srcDir = srcDir
		this.dstDir = dstDir

		// cleanup any old watchers
		stopAndWait()

		// if both paths aren't availiable, then we know nothing about the files
		if (srcDir == null || dstDir == null) {
			return emptyList()
		}

		// look at all the files in the src folder
		for (srcPath in srcDir.listFiles()) {
			srcFilenames.add(srcPath.fileName)
		}

		// start new watchers
		srcWatcher = FilesystemWatcher(srcDir).apply {

			onCreated = { path ->
				srcFilenames.add(path.fileName)
				onWaiting?.invoke(path)
			}
		}

		dstWatcher = FilesystemWatcher(dstDir).apply {

			onCreated = { dstPath ->
				if (dstPath.fileName in srcFilenames) {
					val srcPath = srcDir.resolve(dstPath.fileName)
					val bytesTotal = srcPath.fileSize()
					onStarted?.invoke(dstPath, bytesTotal)
					val bytesTransferred = dstPath.fileSize()
					if (bytesTransferred == bytesTotal) {
						onFinished?.invoke(dstPath)
					} else {
						onProgress?.invoke(dstPath, dstPath.fileSize())
					}
				}
			}

			onChanged = { dstPath ->
				if (dstPath.fileName in srcFilenames) {
					val srcPath = srcDir.resolve(dstPath.fileName)
					val bytesTotal = srcPath.fileSize()
					val bytesTransferred = dstPath.fileSize()
					if (bytesTransferred == bytesTotal) {
						onFinished?.invoke(dstPath)
					} else {
						onProgress?.invoke(dstPath, dstPath.fileSize())
					}
				}
			}
		}

		// return file info for files that are in both folders
		val out = ArrayList<FileInfo>()
		for (dstPath in dstDir.listFiles()) {
			if (dstPath.fileName in srcFilenames) {

				val srcPath = srcDir.resolve(dstPath.fileName)
				val srcSize = srcPath.fileSize()

				val dstSize = if (dstPath.exists()) {
					dstPath.fileSize()
				} else {
					0
				}

				out.add(FileInfo(dstPath, srcSize, dstSize))
			}
		}
		return out
	}

	fun stopAndWait() {

		// cleanup the watchers if needed
		srcWatcher?.stopAndWait()
		srcWatcher = null
		dstWatcher?.stopAndWait()
		dstWatcher = null

		// reset state
		srcFilenames.clear()
	}
}



// for testing
fun main() {

	val dir = Paths.get("/tmp/streamPYP/watcher")
		.createDirsIfNeeded()

	val dirSrc = dir.resolve("src").createDirsIfNeeded()
	val dirDst = dir.resolve("dst").createDirsIfNeeded()

	val watcher = TransferWatcher().apply {

		onWaiting = { path ->
			println("\twaiting: $path")
		}

		onStarted = { path, size ->
			println("\tstarted $path $size bytes")
		}

		onProgress = { path, size ->
			println("\tprogress $path $size bytes")
		}

		onFinished = { path ->
			println("\tfinished $path")
		}
	}
	fun startWatcher() {
		for (info in watcher.watch(dirSrc, dirDst)) {
			println("\tpre ${info.path} ${info.bytesTransfered} of ${info.bytesTotal}")
		}
	}

	// simulate a file transfer
	try {

		startWatcher()

		Thread.sleep(2000)

		// write the src file
		println("writing src ...")
		val fileSrc = dirSrc.resolve("the[5]file.dat")
		fileSrc.writeBytes(ByteArray(1024*2) { 42 })
		println("written!")

		Thread.sleep(2000)

		// copy the file, but slowly
		println("copying file ...")
		val fileDst = dirDst.resolve(fileSrc.fileName)
		fileSrc.inputStream().use { ins ->
			fileDst.outputStream().use { outs ->

				if (!watcher.isWatching) startWatcher()

				val buf = ByteArray(1024)
				while (true) {
					Thread.sleep(2000)
					val size = ins.read(buf)
					if (size <= 0) {
						break
					}
					outs.write(buf, 0, size)
					println("copied $size bytes")
				}
			}
		}
		println("copied!")

		Thread.sleep(2000)

		fileSrc.delete()

		Thread.sleep(2000)

		// cleanup
		fileDst.delete()

	} finally {

		println("stopping ...")
		watcher.stopAndWait()

		println("done!")
	}
}
