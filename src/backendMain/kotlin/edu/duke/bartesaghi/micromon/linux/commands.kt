package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.linux.hostprocessor.Response


data class Command(
	val program: String,
	val args: MutableList<String> = ArrayList(),
	val envvars: MutableList<EnvVar> = ArrayList()
) {

	constructor(program: String, vararg args: String) : this(program, args = args.toMutableList())


	fun wrap(program: String, args: List<String>): Command =
		Command(
			program,
			ArrayList<String>().also {
				it.addAll(args)
				it.add(this.program)
				it.addAll(this.args)
			},
			envvars
		)

	fun toShellSafeString(includeEnvvars: Boolean = true): String {
		val buf = StringBuilder()

		// add envvars
		for (envvar in envvars) {
			if (buf.isNotEmpty()) {
				buf.append(' ')
			}
			buf.append("export ${Posix.quote(envvar.name)}=${Posix.quote(envvar.value)};")
		}

		// add the program
		if (buf.isNotEmpty()) {
			buf.append(' ')
		}
		buf.append(Posix.quote(program))

		// add the args
		for (arg in args) {
			if (buf.isNotEmpty()) {
				buf.append(' ')
			}
			buf.append(Posix.quote(arg))
		}

		return buf.toString()
	}

	fun wrapShell(block: (String) -> String): Command =
		Command(
			"/bin/sh",
			ArrayList<String>().also {
				it.add("-c")
				it.add(block(toShellSafeString(includeEnvvars = false)))
			},
			envvars
		)
}


data class EnvVar(
	val name: String,
	val value: String
) {

	fun toList() = listOf(name, value)
}



interface CommandExecutor {

	data class ExecResult(
		val out: List<String>,
		val err: List<String>,
		/** stdout and stderr, but combined together in realtime */
		val console: List<String>,
		val exitCode: Int
	)

	suspend fun <R> connection(block: suspend Session.() -> R): R

	interface Session {

		/**
		 * Run a remote shell command and return the results
		 *
		 * IMPORTANT: user inputs should be sanitized to prevent exploits!!!
		 */
		suspend fun exec(cmd: Command): ExecResult

		/**
		 * Run a remote shell command and return the results,
		 * throwing an exception if the remote process exited with a nonzero code.
		 *
		 * IMPORTANT: user inputs should be sanitized to prevent exploits!!!
		 */
		suspend fun execOrThrow(cmd: Command): ExecResult
	}
}


class LocalCommandExecutor : CommandExecutor {

	override suspend fun <R> connection(block: suspend CommandExecutor.Session.() -> R): R {
		return block(object : CommandExecutor.Session {

			override suspend fun exec(cmd: Command): CommandExecutor.ExecResult {

				val process = Backend.instance.hostProcessor.execStream(
					cmd,
					stdout = true,
					stderr = true
				)

				val out = ArrayList<String>()
				val err = ArrayList<String>()
				val combined = ArrayList<String>()
				var exitCode: Int

				// wait for the command to finish
				while (true) {
					when (val event = process.recvEvent()) {

						is Response.ProcessEvent.Console -> when (event.kind) {

							Response.ProcessEvent.ConsoleKind.Stdout -> {
								val chunk = event.chunk.toString(Charsets.UTF_8)
								out.add(chunk)
								combined.add(chunk)
							}

							Response.ProcessEvent.ConsoleKind.Stderr -> {
								val chunk = event.chunk.toString(Charsets.UTF_8)
								err.add(chunk)
								combined.add(chunk)
							}
						}

						is Response.ProcessEvent.Fin -> {
							exitCode = event.exitCode
								?: -1024
								// ExecResult requires a number for the exit code,
								// so if the process didn't actually terminate with a code
								// (eg, because of a signal), then pick an arbitrary error code
							break
						}
					}
				}

				return CommandExecutor.ExecResult(out, err, combined, exitCode)
			}

			override suspend fun execOrThrow(cmd: Command): CommandExecutor.ExecResult {
				val result = exec(cmd)
				if (result.exitCode != 0) {
					throw Exception("""
						|Local command failed:
						|cmd:     $cmd
						|console: ${result.console.joinToString("\n         ")}
					""".trimMargin())
				}
				return result
			}
		})
	}
}
