package edu.duke.bartesaghi.micromon.linux.hostprocessor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream


sealed interface Response {

	class Error(val reason: String) : Response {
		companion object {
			const val ID: UInt = 1u
		}
	}

	class Pong : Response {
		companion object {
			const val ID: UInt = 2u
		}
	}

	class Exec(val response: Response) : Response {
		companion object {
			const val ID: UInt = 3u
		}

		sealed interface Response

		class Success(val pid: UInt) : Response {
			companion object {
				const val ID: UInt = 1u
			}
		}

		class Failure(val reason: String) : Response {
			companion object {
				const val ID: UInt = 2u
			}
		}
	}

	class ProcessEvent(val event: Event) : Response {
		companion object {
			const val ID: UInt = 4u
		}

		sealed interface Event

		class Console(val kind: ConsoleKind, val chunk: ByteArray): Event {
			companion object {
				const val ID: UInt = 1u
			}
		}

		class Fin(val exitCode: Int?) : Event {
			companion object {
				const val ID: UInt = 2u
			}
		}

		enum class ConsoleKind(val id: UInt) {

			Stdout(1u),
			Stderr(2u);

			companion object {

				operator fun get(id: UInt): ConsoleKind =
					when (id) {
						Stdout.id -> Stdout
						Stderr.id -> Stderr
						else -> throw NoSuchElementException("unrecognized console kind: $id")
					}
			}
		}
	}

	class Status(val isRunning: Boolean) : Response {
		companion object {
			const val ID: UInt = 5u
		}
	}

	class Username(val username: String?): Response {
		companion object {
			const val ID: UInt = 6u
		}
	}

	class Uid(val uid: UInt?): Response {
		companion object {
			const val ID: UInt = 7u
		}
	}

	class Groupname(val groupname: String?): Response {
		companion object {
			const val ID: UInt = 8u
		}
	}

	class Gid(val gid: UInt?): Response {
		companion object {
			const val ID: UInt = 9u
		}
	}

	class Gids(val gids: List<UInt>?): Response {
		companion object {
			const val ID: UInt = 10u
		}
	}
}


class ResponseEnvelope(
	val id: UInt,
	val response: Response
) {

	fun encode(): ByteArray {

		val bos = ByteArrayOutputStream()
		val out = DataOutputStream(bos)

		out.writeU32(id)

		when (response) {

			is Response.Error -> {
				out.writeU32(Response.Error.ID)
				out.writeUtf8(response.reason)
			}

			is Response.Pong -> {
				// only the id needed here
				out.writeU32(Response.Pong.ID)
			}

			is Response.Exec -> {
				out.writeU32(Response.Exec.ID)
				when (val response = response.response) {
					is Response.Exec.Success -> {
						out.writeU32(Response.Exec.Success.ID)
						out.writeU32(response.pid)
					}
					is Response.Exec.Failure -> {
						out.writeU32(Response.Exec.Failure.ID)
						out.writeUtf8(response.reason)
					}
				}
			}

			is Response.ProcessEvent -> {
				out.writeU32(Response.ProcessEvent.ID)
				when (val event = response.event) {
					is Response.ProcessEvent.Console -> {
						out.writeU32(Response.ProcessEvent.Console.ID)
						out.writeU32(event.kind.id)
						out.writeBytes(event.chunk)
					}
					is Response.ProcessEvent.Fin -> {
						out.writeU32(Response.ProcessEvent.Fin.ID)
						out.writeOption(event.exitCode) {
							out.writeInt(it)
						}
					}
				}
			}

			is Response.Status -> {
				out.writeU32(Response.Status.ID)
				out.writeBoolean(response.isRunning)
			}

			is Response.Username -> {
				out.writeU32(Response.Username.ID)
				out.writeOption(response.username) {
					out.writeUtf8(it)
				}
			}

			is Response.Uid -> {
				out.writeU32(Response.Uid.ID)
				out.writeOption(response.uid) {
					out.writeU32(it)
				}
			}

			is Response.Groupname -> {
				out.writeU32(Response.Groupname.ID)
				out.writeOption(response.groupname) {
					out.writeUtf8(it)
				}
			}

			is Response.Gid -> {
				out.writeU32(Response.Gid.ID)
				out.writeOption(response.gid) {
					out.writeU32(it)
				}
			}

			is Response.Gids -> {
				out.writeU32(Response.Gids.ID)
				out.writeOption(response.gids) { gids ->
					out.writeArray(gids) { gid ->
						out.writeU32(gid)
					}
				}
			}
		}

		return bos.toByteArray()
	}


	companion object {

		fun decode(msg: ByteArray): ResponseEnvelope {

			val input = DataInputStream(ByteArrayInputStream(msg))

			val id = input.readU32()

			val response: Response = when (val responseTypeId = input.readU32()) {

				Response.Error.ID -> Response.Error(
					reason = input.readUtf8()
				)

				Response.Pong.ID -> Response.Pong()

				Response.Exec.ID -> Response.Exec(when (val execTypeId = input.readU32()) {
					Response.Exec.Success.ID -> Response.Exec.Success(
						pid = input.readU32()
					)
					Response.Exec.Failure.ID -> Response.Exec.Failure(
						reason = input.readUtf8()
					)
					else -> throw NoSuchElementException("unrecognized response exec type: $execTypeId")
				})

				Response.ProcessEvent.ID -> Response.ProcessEvent(when (val eventTypeId = input.readU32()) {
					Response.ProcessEvent.Console.ID -> Response.ProcessEvent.Console(
						kind = Response.ProcessEvent.ConsoleKind[input.readU32()],
						chunk = input.readBytes()
					)
					Response.ProcessEvent.Fin.ID -> Response.ProcessEvent.Fin(
						exitCode = input.readOption {
							input.readInt()
						}
					)
					else -> throw NoSuchElementException("unrecognized response process event type: $eventTypeId")
				})

				Response.Status.ID -> Response.Status(
					isRunning = input.readBoolean()
				)

				Response.Username.ID -> Response.Username(
					username = input.readOption {
						input.readUtf8()
					}
				)

				Response.Uid.ID -> Response.Uid(
					uid = input.readOption {
						input.readU32()
					}
				)

				Response.Groupname.ID -> Response.Groupname(
					groupname = input.readOption {
						input.readUtf8()
					}
				)

				Response.Gid.ID -> Response.Gid(
					gid = input.readOption {
						input.readU32()
					}
				)
				Response.Gids.ID -> Response.Gids(
					gids = input.readOption {
						input.readArray {
							input.readU32()
						}
					}
				)


				else -> throw NoSuchElementException("unrecognized response type: $responseTypeId")
			}

			return ResponseEnvelope(id, response)
		}
	}
}
