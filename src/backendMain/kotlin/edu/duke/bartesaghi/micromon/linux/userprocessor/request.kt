package edu.duke.bartesaghi.micromon.linux.userprocessor

import edu.duke.bartesaghi.micromon.linux.hostprocessor.readU32
import edu.duke.bartesaghi.micromon.linux.hostprocessor.writeU32
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

	class Uids: Request {
		companion object {
			const val ID: UInt = 2u
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

			is Request.Uids -> {
				out.writeU32(Request.Uids.ID)
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

				Request.Uids.ID -> Request.Uids()

				else -> throw NoSuchElementException("unrecognized response type id: $typeId")
			}

			return RequestEnvelope(id, request)
		}
	}
}
