package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.linux.Filesystem
import edu.duke.bartesaghi.micromon.services.GlobCount
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.kvision.remote.ServiceException
import io.seruco.encoding.base62.Base62
import kotlinx.coroutines.*
import org.bson.BsonObjectId
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.tomlj.TomlArray
import org.tomlj.TomlPosition
import org.tomlj.TomlTable
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.file.*
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.io.NoSuchFileException
import kotlin.io.path.moveTo
import kotlin.math.abs
import kotlin.streams.toList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds


/**
 * Reads stdout and stderr from a process and sends the lines
 * back in a ConcurrentLinkedQueue instance.
 */
class ProcessStreamer(processBuilder: ProcessBuilder, writer: ((OutputStream)->Unit)? = null) {

	init {
		// combine stdout and stderr so we only have to read one stream
		processBuilder.redirectErrorStream(true)
	}

	// start the process
	private val process = processBuilder.start()

	// read the result in a separate thread
	val console = ConcurrentLinkedQueue<String>()

	private val thread = Thread {
		process.inputStream.bufferedReader().forEachLine { line ->
			console.add(line)
		}
	}.apply {
		name = "ProcessStreamer"
		isDaemon = true
		start()
	}

	val isRunning get() = process.isAlive

	init {
		// write to the input stream if needed
		if (writer != null) {
			process.outputStream.use {
				writer(it)
			}
		}
	}

	fun waitFor(duration: Duration? = null) = apply {
		if (duration != null) {
			val finished = process.waitFor(duration.inWholeMilliseconds, TimeUnit.MILLISECONDS)
			if (finished) {
				// process finished, listener thread will exit on its own
				thread.join()
			} else {
				// process still running, need to disconnect the listener thread
				thread.interrupt()
			}
		} else {
			process.waitFor()
			thread.join()
		}
	}

	val exitCode get() = process.exitValue()
}

fun ProcessBuilder.stream(writer: ((OutputStream)->Unit)? = null) = ProcessStreamer(this, writer)


fun String.toPath() =
	Paths.get(this)

fun Path.existsOrThrow(): Path =
	apply {
		if (!exists()) {
			throw FileNotFoundException(toString())
		}
	}

/**
 * Converts a user-defined string to a safe linux filename,
 * that is also safe for pyp to use.
 * Guaranteed to be as unique as the input string,
 * but still recognizable as the user-defined input.
 */
fun String.toSafeFileName(id: String = this.toFileNameCode()): String =
	"${pypSafeFileName()}-$id"


/**
 * Generate a filesystem-safe code that uniquely represents the given string
 */
fun String.toFileNameCode(): String =
	toByteArray(Charsets.UTF_8)
		.base62Encode()


/**
 * Don't allow malicious inputs like / or . or ..
 * or $ or ``
 */
fun String.sanitizeFileName(): String =
	when (this) {
		"." -> "_"
		".." -> "__"
		else -> replace('/', '_')
			.replace('$', '_')
			.replace('`', '_')
	}


/**
 * Make a file name that is safe for pyp to use.
 *
 * For example, pyp often concatenates file names into paths
 * and then sends them directly into shell interterpers without
 * any quoting or escaping.
 *
 * This function should prevent any benign (or malicious) input from users
 * from causing bugs or security exploits in pyp
 */
private fun String.pypSafeFileName(): String =
	// at the very least, we need to remove the spaces from the names,
	// since pyp sends a lot of unquoted paths to shell interpreters
	replace(' ', '_')
	// also filter out any symbols that will mess with shell interpreters
	// like quotes
	.replace('\'', '_')
	.replace('"', '_')
	// and wildcards
	.replace('*', '_')
	.replace('?', '_')
	.sanitizeFileName()


/**
 * IO operations can run really slowly (especially on NFS filesystems),
 * so always run slow IO operations on the IO thread pool to keep request threads from blocking
 */
suspend fun <R> slowIOs(block: suspend CoroutineScope.() -> R): R =
	withContext(Dispatchers.IO, block)


fun Path.exists() = Files.exists(this)
fun Path.isDirectory() = Files.isDirectory(this)

fun Path.createDirsIfNeeded() = apply {
	if (!exists()) {
		Files.createDirectories(this)
	}
}

fun Path.delete() =
	Files.delete(this)

fun Path.deleteDirRecursively() = apply {
	if (exists() && isDirectory()) {

		// explicitly don't specify the FOLLOW_LINKS option
		// we *VERY MUCH* don't want to follow symlinks here
		val options = setOf<FileVisitOption>()

		Files.walkFileTree(this, options, Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {

			override fun visitFile(file: Path?, attrs: BasicFileAttributes?): FileVisitResult {
				file?.delete()
				return super.visitFile(file, attrs)
			}
			override fun postVisitDirectory(dir: Path?, exc: IOException?): FileVisitResult {
				dir?.delete()
				return super.postVisitDirectory(dir, exc)
			}
		})
	}
}

fun Path.deleteDirRecursivelyAsync() {
	Backend.scope.launch(Dispatchers.IO) {
		deleteDirRecursively()
	}
}

fun Path.mustExist(msg: String? = null) = apply {
	if (!exists()) {
		throw NoSuchFileException(toFile(), reason = msg)
	}
}

fun Path.readString() = toFile().readText()
fun Path.readBytes() = toFile().readBytes()
fun Path.writeString(str: String) = toFile().writeText(str)
// TODO: make async read/write functions that run on IO thread pool?

fun Path.makeExecutable() {
	Files.setPosixFilePermissions(
		this,
		Files.getPosixFilePermissions(this).apply {
			add(PosixFilePermission.OWNER_EXECUTE)
			add(PosixFilePermission.GROUP_EXECUTE)
			add(PosixFilePermission.OTHERS_EXECUTE)
		}
	)
}

fun Path.ctime(): Instant? =
	takeIf { exists() }
	?.let { Files.getAttribute(this, "unix:ctime") as FileTime }
	?.toInstant()

fun Path.listDirs() =
	if (exists()) {
		// NOTE: Files.list() will leak file descriptors until it is closed(), either by GC, or explicitly
		Files.list(this).use {
			it.filter { name -> Files.isDirectory(name) }
			.toList()
		}
	} else {
		emptyList()
	}

fun Path.listFiles() =
	if (exists()) {
		// NOTE: Files.list() will leak file descriptors until it is closed(), either by GC, or explicitly
		Files.list(this).use {
			it.filter { name -> Files.isRegularFile(name) }
			.toList()
		}
	} else {
		emptyList()
	}

/** filename without extension */
fun Path.baseName() =
	fileName.toString().substringBeforeLast('.')

/** get the rest of the path, starting from the specified path element index */
fun Path.rest(i: Int) =
	if (i < nameCount) {
		subpath(i, nameCount)
	} else {
		Paths.get("")
	}

/**
 * Creates a symlink at `link` with `this` as the target.
 * Creates any necessary parent folders of `link`.
 */
fun Path.linkTo(link: Path) {
	val target = this
	link.parent.createDirsIfNeeded()
	Files.createSymbolicLink(link, target)
}

/** renames the path */
fun Path.rename(newName: String) {
	val target = this.parent.resolve(newName)
	this.moveTo(target, false)
}


data class GlobCount(
	val matches: Int,
	val total: Int
)

suspend fun Path.globCount(): GlobCount {

	// split the path into folder and glob
	val folder = parent.toString()
	val glob = fileName.toString()

	// query the filesystem for one folder
	// NOTE: NFS filesystems can very VEEEERY slow!
	// so use an optimized folder listing function rather than the standard Java one
	val files = Filesystem.listFolderFast(folder)
		?: throw ServiceException("File can't be opened as a folder")

	// do the count
	val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
	return GlobCount(
		matched = files.count { matcher.matches(Paths.get(it.name)) },
		total = files.size
	)
}

suspend fun Path.globCountOrNull(): GlobCount? =
	try {
		globCount()
	} catch (t: Throwable) {
		Backend.log.warn("Failed to count GLOB: $this", t.cleanupStackTrace())
		null
	}


class WaitForExistenceOutput(
	val path: Path,
	val timeout: Duration,
	val result: Result
) {

	enum class Result {
		Exists,
		Timeout
	}

	fun orWarn() {
		if (result == Result.Timeout) {
			Backend.log.warn("Waiting for file to exist timed out after $timeout\n\t$path")
		}
	}
}

/**
 * suspends the function (on the IO thread pool) until a file is created and returns true,
 * or a timeout has elapsed and return false
 */
suspend fun Path.waitForExistence(timeout: Duration, interval: Duration = 1.seconds): WaitForExistenceOutput {

	val result = slowIOs {
		val startNs = System.nanoTime()
		while (!exists()) {

			// are we out of time to wait?
			val elapsed = (System.nanoTime() - startNs).nanoseconds
			if (elapsed > timeout) {
				return@slowIOs WaitForExistenceOutput.Result.Timeout
			}

			// nope, can wait a bit more
			delay(interval)
		}

		WaitForExistenceOutput.Result.Exists
	}

	return WaitForExistenceOutput(this, timeout, result)
}


/**
 * Remove all the slashes from a user input,
 * so they can't jump around the filsystem if
 * we build a file system path out of user inputs.
 */
fun String.sanitizePath() =
	replace("/", "")
		.replace("\\", "")


fun String.urlEncode(): String =
	URLEncoder.encode(this, Charsets.UTF_8.displayName())

fun ByteArray.base62Encode(): String =
	Base62.createInstance().encode(this).toString(Charsets.UTF_8)

fun String.base62Decode(): ByteArray =
	Base62.createInstance().decode(this.toByteArray(Charsets.UTF_8))

fun String.toObjectId(): ObjectId =
	ObjectId(base62Decode())

fun ObjectId.toStringId(): String =
	toByteArray().base62Encode()

fun BsonObjectId.toStringId(): String =
	value.toStringId()


/**
 * Exceptions on the server often contain sensitive information that should not be shared with the public.
 * Catch all exceptions and replace them with user-friendly messages instead.
 *
 * Sadly, KVision offers no mechanism to do exception scrubbing globally,
 * so all service methods should wrap their processing with this function.
 *
 * NOTE: this function really only makes sense when used with KVision service functions.
 * Since KVision tries to foward the exception to the client, we need to sanitize it first.
 * But using this function for KTor service functions doesn't really make sense,
 * since KTor does not forward any errors. It just returns HTTP responses.
 * Use respondExceptions() there instead.
 */
inline fun <T> sanitizeExceptions(block: () -> T): T {
	try {
		return block()
	} catch (t: Throwable) {

		// NOTE: the stack traces in service exceptions have a ton of framework spam in them
		//       try to filter it out, but don't destroy any useful stack information

		when (t) {

			// let KVison's service exceptions through, since they're designed for users
			is ServiceException -> throw t.cleanupStackTrace()

			// if authentication exceptions have internal info, log it, and then remove it before passing to client
			is AuthenticationExceptionWithInternal -> {

				LoggerFactory.getLogger("Service").error("Authentication error", t.cleanupStackTrace())

				throw t.external().cleanupStackTrace()
			}

			// let plain authentication exceptions though
			is AuthenticationException -> throw t.cleanupStackTrace()

			// but sanitize everything else
			else -> {

				// log the full exception for debugging
				LoggerFactory.getLogger("Service").error("Service error", t.cleanupStackTrace())

				// but send a clean exception to the user.cleanupStackTrace()
				throw ServiceException("Internal server error").cleanupStackTrace()
			}
		}
	}
}


fun <T:Throwable> T.cleanupStackTrace(): T = apply {

	val out = ArrayList<StackTraceElement>()
	var useful: Boolean? = null
	for (elem in stackTrace.reversed()) {

		// decide if this frame is useful or not
		if (elem.className.startsWith("edu.duke.")) {
			useful = true
		}

		// keep all frames including and above the useful ones
		if (useful == true) {
			out.add(elem)
		} else if (useful == null) {
			useful = false

			// otherwise, show a gap
			out.add(StackTraceElement(".", ".", "omitted", 0))
		}
	}

	// filter the stack trace if we found anything useful
	// otherwise, leave it alone, we'd get a totally empty stack trace if we filtered everything out
	if (useful == true) {
		stackTrace = out.reversed().toTypedArray()
	}
}


fun TomlTable.getTableOrThrow(key: String, pos: TomlPosition? = null): TomlTable =
	getTable(key) ?: throw TomlParseException("missing field \"$key\"", pos)

fun TomlTable.getStringOrThrow(key: String, pos: TomlPosition? = null): String =
	getString(key) ?: throw TomlParseException("missing field \"$key\"", pos)

fun TomlTable.getArrayOrThrow(key: String, pos: TomlPosition? = null): TomlArray =
	getArray(key) ?: throw TomlParseException("missing field \"$key\"", pos)

fun TomlTable.getDoubleOrThrow(key: String, pos: TomlPosition? = null): Double =
	getIntoDouble(key) ?: throw TomlParseException("missing field \"$key\"", pos)

fun TomlTable.getIntOrThrow(key: String, pos: TomlPosition? = null): Int =
	getLong(key)?.toInt() ?: throw TomlParseException("missing field \"$key\"", pos)

fun TomlTable.getBooleanOrThrow(key: String, pos: TomlPosition? = null): Boolean =
	getBoolean(key) ?: throw TomlParseException("missing field \"$key\"", pos)

fun TomlTable.getInt(key: String): Int? =
	getLong(key)?.toInt()

fun TomlArray.getInt(index: Int): Int =
	getLong(index).toInt()

fun TomlTable.getIntoDouble(key: String): Double? =
	when (val v = get(key)) {
		null -> null
		is Double -> v
		is Long -> v.toDouble()
		else -> throw TomlParseException("expected a number for $key, but found $v")
	}

fun <T> TomlTable.mapTables(key: String, block: (TomlTable, TomlPosition)->T): List<T> =
	getArray(key)
		?.let { array ->
			(0 until array.size()).map { i ->
				block(array.getTable(i), array.inputPositionOf(i))
			}
		}
		?: emptyList()

val TomlArray.indices: IntRange get() =
	0 until size()

class TomlParseException(msg: String, pos: TomlPosition? = null) : RuntimeException(
	// HACKHACK: what's wrong with the compiler here?
	// it doesn't like the TomlPosition class somehow
	// this very simple syntax dosen't compile somehow
	//if (pos != null) {
	// so we'll do something similar instead
	if (pos !== null) {
		"$msg at $pos"
	} else {
		msg
	}
)


/**
 * TOML doesn't iterate keys in order (HashMap implementation... ugh),
 * so explicitly sort the keys by source position.
 */
fun TomlTable.keySetInOrder(): List<String> =
	keySet().sortedWith sort@{ a, b ->

		// lexicographical sort based on source position
		val posa = inputPositionOf(a)!!
		val posb = inputPositionOf(b)!!

		// compare lines first
		posa.line().compareTo(posb.line())
			.takeIf { it != 0 }
			?.let { return@sort it }

		// then compare columns
		posa.column().compareTo(posb.column())
			.takeIf { it != 0 }
			?.let { return@sort it }

		// same position
		0
	}


/**
 * Returns a (pseudo) randomly generated Long key
 * that is guaranteed to be unique for the given map.
 */
fun <V> Map<Long,V>.uniqueKey(): Long {

	val rand = Random()
	var id: Long
	do {
		id = abs(rand.nextLong())
	} while (containsKey(id))

	return id
}


/**
 * NOTE: if `block` calls sanitizeExceptions(), it will break some of these responses.
 */
suspend inline fun ApplicationCall.respondExceptions(block: () -> Unit) =
	try {
		block()
	} catch (t: Throwable) {
		when (t) {

			is BadRequestException,
			is MissingRequestParameterException,
			is AuthenticationException,
			is ServiceException -> {
				// remap to an HTTP 400
				respond(HttpStatusCode.BadRequest, t.message ?: "")
			}

			is NoSuchFileException,
			is FileNotFoundException,
			is NotFoundException -> {
				// remap to an HTTP 404
				respond(HttpStatusCode.NotFound, "")
			}

			else -> {
				Backend.log.error("KTor web service failed", t.cleanupStackTrace())
				respond(HttpStatusCode.InternalServerError)
			}
		}
	}
