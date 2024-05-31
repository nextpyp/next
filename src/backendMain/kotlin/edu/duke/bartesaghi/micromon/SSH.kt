package edu.duke.bartesaghi.micromon

import com.jcraft.jsch.*
import java.io.InputStream
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
	}

	data class ExecResult(
		val out: List<String>,
		val err: List<String>,
		/** stdout and stderr, but combined together in realtime */
		val console: List<String>,
		val exitCode: Int
	)

	// NOTE: SFTP isn't implemented (or turned off) on some SSH daemons, so we shouldn't use it
	//       it may also be causing some performance bottlenecks, but it's hard to be sure about that
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
}


class ConsoleReader(stdout: InputStream, stderr: InputStream) {

	val out = ConcurrentLinkedQueue<String>()
	val err = ConcurrentLinkedQueue<String>()
	val combined = ConcurrentLinkedQueue<String>()

	private val threadOut = streamReader("SSH Exec Out", stdout, out, combined)
	private val threadErr = streamReader("SSH Exec Err", stderr, err, combined)

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
