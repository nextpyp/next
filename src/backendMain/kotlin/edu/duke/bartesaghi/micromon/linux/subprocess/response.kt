package edu.duke.bartesaghi.micromon.linux.subprocess

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf


// NOTE: for kotlin-to-kotlin serialization, we can just use an off-the-shelf encoder,
//       like kotlinx.serialization's ProtoBuf implementation

@Serializable
data class ResponseEnvelope(
	val requestId: Long,
	val response: Response
) {

	companion object {

		fun decode(buf: ByteArray): ResponseEnvelope =
			ProtoBuf.decodeFromByteArray(buf)
	}

	fun encode(): ByteArray =
		ProtoBuf.encodeToByteArray(this)
}


@Serializable
sealed class Response {

	@Serializable
	object Pong : Response()

	@Serializable
	sealed class ReadFile : Response() {

		@Serializable
		data class Fail(val reason: String) : ReadFile()

		@Serializable
		class Chunk(val chunk: ByteArray) : ReadFile() {
			override fun toString(): String =
				"Chunk(${chunk.size} bytes)"
				// don't try to write out all the bytes like the default implementation does
		}

		@Serializable
		object Eof : ReadFile()
	}

	@Serializable
	sealed class WriteFile : Response() {

		@Serializable
		data class Fail(val reason: String) : WriteFile()

		@Serializable
		object Ok : WriteFile()
	}
}
