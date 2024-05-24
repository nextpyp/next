package edu.duke.bartesaghi.micromon.linux.userprocessor

import edu.duke.bartesaghi.micromon.linux.hostprocessor.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream


sealed interface Request {

	object Ping : Request {
		const val ID: UInt = 1u
	}

	object Uids : Request {
		const val ID: UInt = 2u
	}

	data class ReadFile(
		val path: String
	) : Request {
		companion object {
			const val ID: UInt = 3u
		}
	}

	data class WriteFile(val request: Request) : Request {
		companion object {
			const val ID: UInt = 4u
		}

		sealed interface Request

		data class Open(
			val path: String,
			val append: Boolean
		) : Request {
			companion object {
				const val ID: UInt = 1u
			}
		}

		class Chunk(
			val sequence: UInt,
			val data: ByteArray
		) : Request {
			companion object {
				const val ID: UInt = 2u
			}

			// override toString(), since the default impl doesn't write anything useful
			override fun toString(): String =
				"Chunk[sequence=$sequence, data=${data.size} bytes]"

			// because arrays in the JVM are old and dumb =(
			override fun equals(other: Any?): Boolean =
				other is Chunk
					&& other.sequence == this.sequence
					&& other.data.contentEquals(this.data)

			override fun hashCode(): Int {
				var result = sequence.hashCode()
				result = 31*result + data.contentHashCode()
				return result
			}
		}

		data class Close(
			val sequence: UInt
		) : Request {
			companion object {
				const val ID: UInt = 3u
			}
		}
	}
}

fun Request.WriteFile.Request.into(): Request =
	Request.WriteFile(this)


class RequestEnvelope(
	val requestId: UInt,
	val request: Request
) {
	fun encode(): ByteArray {

		val bos = ByteArrayOutputStream()
		val out = DataOutputStream(bos)

		out.writeU32(requestId)

		when (request) {

			is Request.Ping -> {
				// only the id needed here
				out.writeU32(Request.Ping.ID)
			}

			is Request.Uids -> {
				out.writeU32(Request.Uids.ID)
			}

			is Request.ReadFile -> {
				out.writeU32(Request.ReadFile.ID)
				out.writeUtf8(request.path)
			}

			is Request.WriteFile -> {
				out.writeU32(Request.WriteFile.ID)
				when (val request = request.request) {

					is Request.WriteFile.Open -> {
						out.writeU32(Request.WriteFile.Open.ID)
						out.writeUtf8(request.path)
						out.writeBoolean(request.append)
					}

					is Request.WriteFile.Chunk -> {
						out.writeU32(Request.WriteFile.Chunk.ID)
						out.writeU32(request.sequence)
						out.writeBytes(request.data)
					}

					is Request.WriteFile.Close -> {
						out.writeU32(Request.WriteFile.Close.ID)
						out.writeU32(request.sequence)
					}
				}
			}
		}

		return bos.toByteArray()
	}

	companion object {

		fun decode(msg: ByteArray): RequestEnvelope {

			val input = DataInputStream(ByteArrayInputStream(msg))

			val id = input.readU32()

			val request: Request = when (val typeId = input.readU32()) {

				Request.Ping.ID -> Request.Ping

				Request.Uids.ID -> Request.Uids

				Request.ReadFile.ID -> Request.ReadFile(
					path = input.readUtf8(),
				)

				Request.WriteFile.ID -> Request.WriteFile(run {
					when (val writeFileResponseTypeId = input.readU32()) {

						Request.WriteFile.Open.ID -> Request.WriteFile.Open(
							path = input.readUtf8(),
							append = input.readBoolean()
						)

						Request.WriteFile.Chunk.ID -> Request.WriteFile.Chunk(
							sequence = input.readU32(),
							data = input.readBytes()
						)

						Request.WriteFile.Close.ID -> Request.WriteFile.Close(
							sequence = input.readU32()
						)

						else -> throw NoSuchElementException("unrecognized write file type id: $writeFileResponseTypeId")
					}
				})

				else -> throw NoSuchElementException("unrecognized response type id: $typeId")
			}

			return RequestEnvelope(id, request)
		}
	}
}
