package edu.duke.bartesaghi.micromon.linux.hostprocessor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream


sealed interface Request {

	class Ping : Request {
		companion object {
			const val ID: UInt = 1u
		}
	}

	class Exec(
		val program: String,
		val args: List<String>,
		val dir: String?,
		val envvars: List<Pair<String,String>>,
		val stdin: Stdin,
		val stdout: Stdout,
		val stderr: Stderr,
		val streamFin: Boolean
	) : Request {
		companion object {
			const val ID: UInt = 2u
		}

		sealed interface Stdin {

			object Stream : Stdin {
				const val ID: UInt = 1u
			}

			object Ignore : Stdin {
				const val ID: UInt = 2u
			}
		}

		sealed interface Stdout {

			object Stream : Stdout {
				const val ID: UInt = 1u
			}

			data class Write(val path: String) : Stdout {
				companion object {
					const val ID: UInt = 2u
				}
			}

			object Log : Stdout {
				const val ID: UInt = 3u
			}

			object Ignore : Stdout {
				const val ID: UInt = 4u
			}
		}

		sealed interface Stderr {

			object Stream : Stderr {
				const val ID: UInt = 1u
			}

			data class Write(val path: String) : Stderr {
				companion object {
					const val ID: UInt = 2u
				}
			}

			object Merge : Stderr {
				const val ID: UInt = 3u
			}

			object Log : Stderr {
				const val ID: UInt = 4u
			}

			object Ignore : Stderr {
				const val ID: UInt = 5u
			}
		}
	}

	class Status(
		val pid: UInt
	) : Request {
		companion object {
			const val ID: UInt = 3u
		}
	}

	class WriteStdin(
		val pid: UInt,
		val chunk: ByteArray
	) : Request {
		companion object {
			const val ID: UInt = 4u
		}
	}

	class CloseStdin(
		val pid: UInt
	) : Request {
		companion object {
			const val ID: UInt = 5u
		}
	}

	class Kill(
		val signal: Signal,
		val pid: UInt,
		val processGroup: Boolean
	) : Request {
		companion object {
			const val ID: UInt = 6u
		}

		enum class Signal(val id: String) {

			Interrupt("SIGINT"),
			Kill("SIGKILL");

			companion object {
				operator fun get(id: String): Signal? =
					values().find { it.id == id }
			}
		}
	}

	class Username(
		val uid: UInt
	) : Request {
		companion object {
			const val ID: UInt = 7u
		}
	}

	class Uid(
		val username: String
	) : Request {
		companion object {
			const val ID: UInt = 8u
		}
	}

	class Groupname(
		val gid: UInt
	) : Request {
		companion object {
			const val ID: UInt = 9u
		}
	}

	class Gid(
		val groupname: String
	) : Request {
		companion object {
			const val ID: UInt = 10u
		}
	}

	class Gids(
		val uid: UInt
	) : Request {
		companion object {
			const val ID: UInt = 11u
		}
	}
}


class RequestEnvelope(
	val id: UInt,
	val request: Request
) {
	fun encode(): ByteArray {

		val bos = ByteArrayOutputStream()
		val out = DataOutputStream(bos)

		out.writeU32(id)

		when (request) {

			is Request.Ping -> {
				// only the id needed here
				out.writeU32(Request.Ping.ID)
			}

			is Request.Exec -> {
				out.writeU32(Request.Exec.ID)
				out.writeUtf8(request.program)
				out.writeArray(request.args) {
					out.writeUtf8(it)
				}
				out.writeOption(request.dir) {
					out.writeUtf8(it)
				}
				out.writeArray(request.envvars) { (k, v) ->
					out.writeUtf8(k)
					out.writeUtf8(v)
				}
				when (request.stdin) {
					Request.Exec.Stdin.Stream -> {
						out.writeU32(Request.Exec.Stdin.Stream.ID)
					}
					Request.Exec.Stdin.Ignore -> {
						out.writeU32(Request.Exec.Stdin.Ignore.ID)
					}
				}
				when (request.stdout) {
					Request.Exec.Stdout.Stream -> {
						out.writeU32(Request.Exec.Stdout.Stream.ID)
					}
					is Request.Exec.Stdout.Write -> {
						out.writeU32(Request.Exec.Stdout.Write.ID)
						out.writeUtf8(request.stdout.path)
					}
					Request.Exec.Stdout.Log -> {
						out.writeU32(Request.Exec.Stdout.Log.ID)
					}
					Request.Exec.Stdout.Ignore -> {
						out.writeU32(Request.Exec.Stdout.Ignore.ID)
					}
				}
				when (request.stderr) {
					Request.Exec.Stderr.Stream -> {
						out.writeU32(Request.Exec.Stderr.Stream.ID)
					}
					is Request.Exec.Stderr.Write -> {
						out.writeU32(Request.Exec.Stderr.Write.ID)
						out.writeUtf8(request.stderr.path)
					}
					Request.Exec.Stderr.Merge -> {
						out.writeU32(Request.Exec.Stderr.Merge.ID)
					}
					Request.Exec.Stderr.Log -> {
						out.writeU32(Request.Exec.Stderr.Log.ID)
					}
					Request.Exec.Stderr.Ignore -> {
						out.writeU32(Request.Exec.Stderr.Ignore.ID)
					}
				}
				out.writeBoolean(request.streamFin)
			}

			is Request.Status -> {
				out.writeU32(Request.Status.ID)
				out.writeU32(request.pid)
			}

			is Request.WriteStdin -> {
				out.writeU32(Request.WriteStdin.ID)
				out.writeU32(request.pid)
				out.writeBytes(request.chunk)
			}

			is Request.CloseStdin -> {
				out.writeU32(Request.CloseStdin.ID)
				out.writeU32(request.pid)
			}

			is Request.Kill -> {
				out.writeU32(Request.Kill.ID)
				out.writeUtf8(request.signal.id)
				out.writeU32(request.pid)
				out.writeBoolean(request.processGroup)
			}

			is Request.Username -> {
				out.writeU32(Request.Username.ID)
				out.writeU32(request.uid)
			}

			is Request.Uid -> {
				out.writeU32(Request.Uid.ID)
				out.writeUtf8(request.username)
			}

			is Request.Groupname -> {
				out.writeU32(Request.Groupname.ID)
				out.writeU32(request.gid)
			}

			is Request.Gid -> {
				out.writeU32(Request.Gid.ID)
				out.writeUtf8(request.groupname)
			}

			is Request.Gids -> {
				out.writeU32(Request.Gids.ID)
				out.writeU32(request.uid)
			}
		}

		return bos.toByteArray()
	}

	companion object {

		fun decode(msg: ByteArray): RequestEnvelope {

			val input = DataInputStream(ByteArrayInputStream(msg))

			val id = input.readU32()

			val request: Request = when (val typeId = input.readU32()) {

				Request.Ping.ID -> Request.Ping()

				Request.Exec.ID -> Request.Exec(
					program = input.readUtf8(),
					args = input.readArray {
						input.readUtf8()
					},
					dir = input.readOption {
						input.readUtf8()
					},
					envvars = input.readArray {
						input.readUtf8() to input.readUtf8()
					},
					stdin = when (val stdinTypeId = input.readU32()) {
						Request.Exec.Stdin.Stream.ID -> Request.Exec.Stdin.Stream
						Request.Exec.Stdin.Ignore.ID -> Request.Exec.Stdin.Ignore
						else -> throw NoSuchElementException("unrecognized exec stdin type id: $stdinTypeId")
					},
					stdout = when (val stdoutTypeId = input.readU32()) {
						Request.Exec.Stdout.Stream.ID -> Request.Exec.Stdout.Stream
						Request.Exec.Stdout.Write.ID -> Request.Exec.Stdout.Write(
							path = input.readUtf8()
						)
						Request.Exec.Stdout.Log.ID -> Request.Exec.Stdout.Log
						Request.Exec.Stdout.Ignore.ID -> Request.Exec.Stdout.Ignore
						else -> throw NoSuchElementException("unrecognized exec stdout type id: $stdoutTypeId")
					},
					stderr = when (val stderrTypeId = input.readU32()) {
						Request.Exec.Stderr.Stream.ID -> Request.Exec.Stderr.Stream
						Request.Exec.Stderr.Write.ID -> Request.Exec.Stderr.Write(
							path = input.readUtf8()
						)
						Request.Exec.Stderr.Merge.ID -> Request.Exec.Stderr.Merge
						Request.Exec.Stderr.Log.ID -> Request.Exec.Stderr.Log
						Request.Exec.Stderr.Ignore.ID -> Request.Exec.Stderr.Ignore
						else -> throw NoSuchElementException("unrecognized exec stderr type id: $stderrTypeId")
					},
					streamFin = input.readBoolean()
				)

				Request.Status.ID -> Request.Status(
					pid = input.readU32()
				)

				Request.WriteStdin.ID -> Request.WriteStdin(
					pid = input.readU32(),
					chunk = input.readBytes()
				)

				Request.CloseStdin.ID -> Request.CloseStdin(
					pid = input.readU32()
				)

				Request.Kill.ID -> Request.Kill(
					signal = input.readUtf8().let { s ->
						Request.Kill.Signal[s]
							?: throw NoSuchElementException("unrecognized kill signal: $s")
					},
					pid = input.readU32(),
					processGroup = input.readBoolean()
				)

				Request.Username.ID -> Request.Username(
					uid = input.readU32()
				)

				Request.Uid.ID -> Request.Uid(
					username = input.readUtf8()
				)

				Request.Groupname.ID -> Request.Groupname(
					gid = input.readU32()
				)

				Request.Gid.ID -> Request.Gid(
					groupname = input.readUtf8()
				)

				Request.Gids.ID -> Request.Gids(
					uid = input.readU32()
				)

				else -> throw NoSuchElementException("unrecognized request type id: $typeId")
			}

			return RequestEnvelope(id, request)
		}
	}
}
