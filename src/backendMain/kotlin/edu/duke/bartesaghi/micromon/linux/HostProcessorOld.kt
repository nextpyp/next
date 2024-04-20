package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.slowIOs
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream


/**
 * Allows micromon to break outside of the container and run commands on the host OS,
 * as long as the host OS has a script listening to the named pipe
 */
object HostProcessorOld {

	val pipePath = Paths.get("/tmp/micromon.hostprocessor.pipe")


	enum class ProcessState(val code: Char) {

		RunningOrRunnable('R'),
		UninterruptibleSleep('D'),
		InterruptableSleep('S'),
		Stopped('T'),
		Zombie('Z');


		companion object {

			operator fun get(code: Char?): ProcessState? =
				values().firstOrNull { it.code == code }
		}
	}


	private suspend fun cmd(command: String): String = slowIOs {

		pipePath.outputStream(StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC).use { pipe ->

			// DEBUG
			//println("HostProcessor cmd: $command")

			// write out the command
			// do the character encoding manually
			pipe.write(Charsets.UTF_8.encode(command).array())

			// NOTE: don't use functions like writeUTF() !!
			// they prepend a string length to the byte stream, which we very don't want
			//pipe.writeUTF(command)
		}

		pipePath.inputStream().use { pipe ->

			// wait for a response
			pipe.readAllBytes().toString(Charsets.UTF_8)
				// DEBUG
				//.also { println("HostProcessor response: $it") }
		}
	}

	suspend fun exec(command: String, out: Path): Long {

		val result = cmd("exec $out $command")

		// the result should be the process pid, followed by a newline
		return result
			.trim()
			.toLong()
	}

	suspend fun status(pid: Long): ProcessState? {

		val result = cmd("status $pid")

		// results can look like:
		//State:	S (sleeping)
		// or a blank line

		return result
			.takeIf { it.startsWith("State:") }
			?.substring("State:".length)
			?.trim()
			?.get(0)
			.let { ProcessState[it] }
	}

	suspend fun kill(pid: Long) {
		cmd("kill $pid")
	}

	suspend fun username(uid: Int): String? =
		cmd("username $uid")
			.takeIf { it.isNotEmpty() }

	suspend fun tryUsername(uid: Int): String =
		try {
			username(uid)
		} catch (t: Throwable) {
			Backend.log.error("Failed to get username for uid=$uid", t)
			null
		} ?: uid.toString()

	suspend fun uid(username: String): Int? =
		cmd("uid $username")
			.trim()
			.takeIf { it.isNotEmpty() }
			?.toIntOrNull()

	suspend fun groupname(gid: Int): String? =
		cmd("groupname $gid")
			.takeIf { it.isNotEmpty() }

	suspend fun tryGroupname(gid: Int): String =
		try {
			groupname(gid)
		} catch (t: Throwable) {
			Backend.log.error("Failed to get groupname for gid=$gid", t)
			null
		} ?: gid.toString()

	suspend fun gid(groupname: String): Int? =
		cmd("gid $groupname")
			.trim()
			.takeIf { it.isNotEmpty() }
			?.toIntOrNull()

	suspend fun gids(uid: Int): List<Int>? =
		cmd("gids $uid")
			.takeIf { it.isNotEmpty() }
			?.split(" ")
			?.mapNotNull { it.trim().toIntOrNull() }
}
