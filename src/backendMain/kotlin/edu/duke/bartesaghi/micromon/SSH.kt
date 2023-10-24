package edu.duke.bartesaghi.micromon

import com.jcraft.jsch.*
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong


object SSH {

	interface Connection {

		/**
		 * Run a remote shell command and return the results
		 *
		 * IMPORTANT: user inputs should be sanitized to prevent exploits!!!
		 */
		fun exec(cmd: String): ExecResult

		/**
		 * Run a remote shell command and return the results,
		 * throwing an exception if the remote process exited with a nonzero code.
		 *
		 * IMPORTANT: user inputs should be sanitized to prevent exploits!!!
		 */
		fun execOrThrow(cmd: String): ExecResult

		fun <R> sftp(block: SFTP.() -> R): R

		fun mkdirs(remote: Path) {
			exec("mkdir -p \"$remote\"")
		}

		/**
		 * Essentially `chmod +x`
		 */
		fun makeExecutable(remote: Path) {
			exec("chmod +x \"$remote\"")
		}
	}

	data class ExecResult(
		val out: List<String>,
		val err: List<String>,
		/** stdout and stderr, but combined together in realtime */
		val console: List<String>,
		val exitCode: Int
	)

	interface SFTP {

		/**
		 * Copy a local file to the remote host.
		 */
		fun upload(local: Path, remote: Path)

		/**
		 * Write a text file to the remote host.
		 */
		fun uploadText(remote: Path, text: String)

		/**
		 * Copy a file from the remote host to the local filesystem.
		 */
		fun download(remote: Path, local: Path)

		/**
		 * Read a text file from the remote host.
		 */
		fun downloadText(remote: Path): String

		/**
		 * Delete a file from the remote host.
		 */
		fun delete(remote: Path)
	}
}

class SSHPool(val config: SshPoolConfig) {

	private var connections = LinkedBlockingDeque<SSH.Connection>(config.maxConnections).apply {

		// pre-create all the connections, but don't open them yet
		val capacity = remainingCapacity()
		for (i in 0 until capacity) {
			addFirst(SSHConnection(i, config))
		}
	}

	suspend fun <R> connection(block: SSH.Connection.() -> R): R =

		// doing blocking IO, so run this on an IO thread pool
		slowIOs ctx@{

			// get an available connection, or wait for one to become available
			val connection = connections.takeFirst()

			try {
				return@ctx connection.block()
			} finally {

				// put the connection back in the pool when we're done with it
				connections.addFirst(connection)
			}
		}
}


/**
 * SftpExceptions have a bug where they don't show the error code in the error message,
 * so translate them into a more useful error message instead.
 */
fun SftpException.translate(vararg args: Pair<String,Any>): Exception {

	return when (id) {

		ChannelSftp.SSH_FX_NO_SUCH_FILE -> NoRemoteFileException(args.joinToString(", ") { (k,v) -> "$k=$v" })

		else -> IOException("""
			|SFTP Error:
			|    id:  $id = ${id.toSftpErrorMsg()}
			|   msg:  $message
			|  args:  ${args.joinToString("\n         ") { (k,v) -> "$k = $v" }}
		""".trimMargin())
	}
}

/**
 * https://winscp.net/eng/docs/sftp_codes
 */
fun Int.toSftpErrorMsg(): String =
	when (this) {
		0 -> "OK: Indicates successful completion of the operation."
		1 -> "EOF: An attempt to read past the end-of-file was made; or, there are no more directory entries to return."
		2 -> "No such file 	A reference was made to a file which does not exist."
		3 -> "Permission denied: The user does not have sufficient permissions to perform the operation."
		4 -> "Failure: An error occurred, but no specific error code exists to describe the failure. This error message should always have meaningful text in the error message field."
		5 -> "Bad message: A badly formatted packet or other SFTP protocol incompatibility was detected."
		6 -> "No connection: There is no connection to the server. This error may be used locally, but must not be return by a server."
		7 -> "Connection lost: The connection to the server was lost. This error may be used locally, but must not be return by a server."
		8 -> "Operation unsupported: An attempted operation could not be completed by the server because the server does not support the operation. It may be returned by the server if the server does not implement an operation."
		9 -> "Invalid handle: The handle value was invalid."
		10 -> "No such path: The file path does not exist or is invalid."
		11 -> "File already exists: The file already exists."
		12 -> "Write protect: The file is on read-only media, or the media is write protected."
		13 -> "No media: The requested operation cannot be completed because there is no media available in the drive."
		14 -> "No space on file-system: The requested operation cannot be completed because there is insufficient free space on the filesystem."
		15 -> "Quota exceeded: The operation cannot be completed because it would exceed the user’s storage quota."
		16 -> "Unknown principal: A principal referenced by the request (either the owner, group, or who field of an ACL), was unknown."
		17 -> "Lock conflict: The file could not be opened because it is locked by another process."
		18 -> "Directory not empty: The directory is not empty."
		19 -> "Not a directory: The specified file is not a directory."
		20 -> "Invalid filename: The filename is not valid."
		21 -> "Link loop: Too many symbolic links encountered or, an SSH_FXF_NOFOLLOW open encountered a symbolic link as the final component"
		22 -> "Cannot delete: The file cannot be deleted. One possible reason is that the advisory read-only attribute-bit is set."
		23 -> "Invalid parameter: One of the parameters was out of range, or the parameters specified cannot be used together."
		24 -> "File is a directory: The specified file was a directory in a context where a directory cannot be used."
		25 -> "Range lock conflict: A read or write operation failed because another process’s mandatory byte-range lock overlaps with the request."
		26 -> "Range lock refused: A request for a byte range lock was refused."
		27 -> "Delete pending: An operation was attempted on a file for which a delete operation is pending."
		28 -> "File corrupt: The file is corrupt; an filesystem integrity check should be run."
		29 -> "Owner invalid: The principal specified can not be assigned as an owner of a file."
		30 -> "Group invalid: The principal specified can not be assigned as the primary group of a file."
		31 -> "No matching byte range lock: The requested operation could not be completed because the specified byte range lock has not been granted."
		else -> "(Unknown)"
	}

class NoRemoteFileException(val args: String)
	: RuntimeException("Remote file did not exist:\n\t$args")


class SSHConnection(
	val id: Int,
	val config: SshPoolConfig
) : SSH.Connection {

	private val jsch = JSch().apply {
		addIdentity(config.key.toString())
	}

	private val lock = Object()
	private var startNs = AtomicLong(0L)
	@Volatile private var session: Session? = null

	private fun resetTimeout() {
		startNs.set(System.nanoTime())
	}

	private fun elapsedSeconds() =
		(System.nanoTime() - startNs.get())/1_000_000_000L

	/**
	 * Starts a new SSH session, or reuses the one that's already open
	 */
	private fun <R> session(block: Session.() -> R): R {

		// use an existing session if possible, but make sure it's still conected
		// NOTE: lock while using the session to block the timeout thread
		synchronized(lock) {
			val s = session
			if (s?.isConnected == true) {
				val result = s.block()
				resetTimeout()
				return result
			}
		}

		// otherwise, make a new session
		val s = jsch.getSession(config.user, config.host, config.port).apply {

			// only connect with public key auth
			setConfig("PreferredAuthentications", "publickey")

			userInfo = object : UserInfo {

				override fun promptPassphrase(message: String?) = false
				override fun getPassphrase() = ""
				override fun getPassword() = ""
				override fun showMessage(message: String?) = Unit
				override fun promptPassword(message: String?) = false

				override fun promptYesNo(message: String?): Boolean {

					// auto-accept the connect-to-new-host prompt
					if (message != null
						&& message.startsWith("The authenticity of host")
						&& message.endsWith("Are you sure you want to continue connecting?")
					) {
						return true
					}

					// reject all other prompts
					return false
				}
			}

			// capture the JSch log for debugging
			val log = StringBuilder()
			JSch.setLogger(object : Logger {
				override fun isEnabled(level: Int) = true
				override fun log(level: Int, message: String?) {
					val levelstr = when (level) {
						Logger.DEBUG -> "DEBUG"
						Logger.INFO -> "INFO"
						Logger.WARN -> "WARN"
						Logger.ERROR -> "ERROR"
						Logger.FATAL -> "FATAL"
						else -> "???"
					}
					log.append("\t$levelstr: $message\n")
				}
			})

			try {
				connect()
			} catch (t: Throwable) {
				throw RuntimeException("""
					|Error connecting to SSH
					|   server:  ${config.host}:${config.port}
					|     user:  ${config.user}
					|JschLog:
					|$log
				""".trimMargin(), t)
			}
		}

		synchronized(lock) {

			session = s

			val result = s.block()

			// start a thread to close the connection after an idle period
			resetTimeout()
			Thread {

				while (elapsedSeconds() < config.timeoutSeconds) {
					Thread.sleep(1_000)
				}

				synchronized(lock) {
					s.disconnect()
					session = null
				}

			}.apply {
				name = "SSH Timeout"
				isDaemon = true
				start()
			}

			return result
		}
	}

	override fun exec(cmd: String): SSH.ExecResult = session {

		val channel = openChannel("exec") as ChannelExec
		channel.setCommand(cmd)

		// start threads to read stdout and stderr
		val console = ConsoleReader(channel.inputStream, channel.errStream)

		// send the command and wait for it to finish
		channel.connect()

		// finish reading the result
		try {
			console.waitForFinish()
		} finally {
			channel.disconnect()
		}

		return@session SSH.ExecResult(console.out.toList(), console.err.toList(), console.combined.toList(), channel.exitStatus)
	}

	override fun execOrThrow(cmd: String): SSH.ExecResult {
		val result = exec(cmd)
		if (result.exitCode != 0) {
			throw Exception("""
					|SSH command failed:
					|user:    ${config.user}
					|host:    ${config.host}
					|cmd:     $cmd
					|console: ${result.console.joinToString("\n         ")}
				""".trimMargin())
		}
		return result
	}

	override fun <R> sftp(block: SSH.SFTP.() -> R): R = session {
		val channel = openChannel("sftp") as ChannelSftp
		channel.connect()
		try {
			return@session SFTP(channel).block()
		} finally {
			channel.disconnect()
		}
	}

	class SFTP(private val channel: ChannelSftp) : SSH.SFTP {

		override fun upload(local: Path, remote: Path) {
			try {
				Files.newInputStream(local).use {
					channel.put(it, remote.toString())
				}
			} catch (ex: SftpException) {
				throw ex.translate(
					"local" to local,
					"remote" to remote
				)
			}
		}

		override fun uploadText(remote: Path, text: String) {
			try {
				channel.put(remote.toString()).writer(Charsets.UTF_8).use {
					it.write(text)
				}
			} catch (ex: SftpException) {
				throw ex.translate(
					"remote" to remote
				)
			}
		}

		override fun download(remote: Path, local: Path) {
			try {
				Files.newOutputStream(local).use {
					channel.get(remote.toString(), it)
				}
			} catch (ex: SftpException) {
				throw ex.translate(
					"remote" to remote,
					"local" to local
				)
			}
		}

		override fun downloadText(remote: Path): String {
			try {
				channel.get(remote.toString()).reader(Charsets.UTF_8).use {
					return it.readText()
				}
			} catch (ex: SftpException) {
				throw ex.translate(
					"remote" to remote
				)
			}
		}

		override fun delete(remote: Path) {
			try {
				channel.rm(remote.toString())
			} catch (ex: SftpException) {
				when (val ex2 = ex.translate("remote" to remote)) {
					is NoRemoteFileException -> Unit // ignore missing files, we're trying to delete them after all
					else -> throw ex2
				}
			}
		}
	}
}


class ConsoleReader(stdout: InputStream, stderr: InputStream) {

	val out = ConcurrentLinkedQueue<String>()
	val err = ConcurrentLinkedQueue<String>()
	val combined = ConcurrentLinkedQueue<String>()

	val threadOut = streamReader("SSH Exec Out", stdout, out, combined)
	val threadErr = streamReader("SSH Exec Err", stderr, err, combined)

	fun waitForFinish() {
		threadOut.join()
		threadErr.join()
	}
}

fun streamReader(name: String, stream: InputStream, vararg outputs: MutableCollection<String>) =
	Thread {
		stream.bufferedReader().forEachLine { line ->
			for (output in outputs) {
				output.add(line)
			}
		}
	}.apply {
		this.name = name
		isDaemon = true
		start()
	}


data class SshPoolConfig(
	val user: String,
	val host: String,
	val key: Path = defaultKeyPath,
	val port: Int = defaultPort,
	val maxConnections: Int = defaultPoolSize,
	val timeoutSeconds: Int = defaultTimeoutSeconds
) {

	companion object {

		val defaultKeyPath: Path =
			java.nio.file.Paths.get(System.getProperty("user.home")).resolve(".ssh/id_rsa")

		const val defaultPort: Int = 22

		/**
		 * typical sshd config is something like:
		 *   MaxSessions 10
		 *   MaxStartups 10:30:100
		 * which means we can usually get up to 10 connections from one client without problems
		 */
		const val defaultPoolSize: Int = 8

		/**
		 * Aritrarily chosen
		 * 5 minutes seems a bit high, but we can keep it until we find a reason to change it
		 */
		const val defaultTimeoutSeconds: Int = 5*60
	}
}
