package edu.duke.bartesaghi.micromon.linux.subprocess

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf


// NOTE: for kotlin-to-kotlin serialization, we can just use an off-the-shelf encoder,
//       like kotlinx.serialization's ProtoBuf implementation

@Serializable
data class RequestEnvelope(
	val requestId: Long,
	val request: Request
) {

	companion object {

		fun decode(buf: ByteArray): RequestEnvelope =
			ProtoBuf.decodeFromByteArray(buf)
	}

	fun encode(): ByteArray =
		ProtoBuf.encodeToByteArray(this)
}


@Serializable
sealed class Request {

	@Serializable
	object Ping : Request()

	@Serializable
	data class ReadFile(val path: String) : Request()

	@Serializable
	sealed class WriteFile : Request() {

		@Serializable
		data class Open(val path: String) : WriteFile()

		@Serializable
		class Chunk(val chunk: ByteArray) : WriteFile() {
			override fun toString(): String =
				"Chunk(${chunk.size} bytes)"
				// don't try to write out all the bytes like the default implementation does
		}

		@Serializable
		object Close : WriteFile()
	}
}
