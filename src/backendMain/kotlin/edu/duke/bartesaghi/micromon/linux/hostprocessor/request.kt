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
		val streamStdin: Boolean,
		val streamStdout: Boolean,
		val streamStderr: Boolean,
		val streamFin: Boolean
	) : Request {
		companion object {
			const val ID: UInt = 2u
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
		val pid: UInt
	) : Request {
		companion object {
			const val ID: UInt = 6u
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
				out.writeBoolean(request.streamStdin)
				out.writeBoolean(request.streamStdout)
				out.writeBoolean(request.streamStderr)
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
				out.writeU32(request.pid)
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
					streamStdin = input.readBoolean(),
					streamStdout = input.readBoolean(),
					streamStderr = input.readBoolean(),
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
					pid = input.readU32()
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

				else -> throw NoSuchElementException("unrecognized response type id: $typeId")
			}

			return RequestEnvelope(id, request)
		}
	}
}
