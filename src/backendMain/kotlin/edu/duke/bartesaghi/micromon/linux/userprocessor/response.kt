package edu.duke.bartesaghi.micromon.linux.userprocessor

import edu.duke.bartesaghi.micromon.linux.hostprocessor.readU32
import edu.duke.bartesaghi.micromon.linux.hostprocessor.readUtf8
import edu.duke.bartesaghi.micromon.linux.hostprocessor.writeU32
import edu.duke.bartesaghi.micromon.linux.hostprocessor.writeUtf8
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

	class Uids(val uid: UInt, val euid: UInt) : Response {
		companion object {
			const val ID: UInt = 3u
		}
	}
}


class ResponseEnvelope(
	val requestId: UInt,
	val response: Response
) {

	fun encode(): ByteArray {

		val bos = ByteArrayOutputStream()
		val out = DataOutputStream(bos)

		out.writeU32(requestId)

		when (response) {

			is Response.Error -> {
				out.writeU32(Response.Error.ID)
				out.writeUtf8(response.reason)
			}

			is Response.Pong -> {
				// only the id needed here
				out.writeU32(Response.Pong.ID)
			}

			is Response.Uids -> {
				out.writeU32(Response.Uids.ID)
				out.writeU32(response.uid)
				out.writeU32(response.euid)
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

				Response.Uids.ID -> Response.Uids(
					uid = input.readU32(),
					euid = input.readU32()
				)

				else -> throw NoSuchElementException("unrecognized response type: $responseTypeId")
			}

			return ResponseEnvelope(id, response)
		}
	}
}
