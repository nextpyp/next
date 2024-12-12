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

	data class Chmod(val path: String, val ops: List<Op>) : Request {
		companion object {
			const val ID: UInt = 5u
		}

		data class Op(
			val value: Boolean,
			val bits: List<Bit>
		)

		enum class Bit(val pos: UByte) {

			OtherExecute(0u),
			OtherWrite(1u),
			OtherRead(2u),
			GroupExecute(3u),
			GroupWrite(4u),
			GroupRead(5u),
			UserExecute(6u),
			UserWrite(7u),
			UserRead(8u),
			Sticky(9u),
			SetGid(10u),
			SetUid(11u);

			companion object {
				operator fun get(pos: UByte): Bit =
					values()
						.firstOrNull { it.pos == pos }
						?: throw NoSuchElementException("unrecognized Chmod bit pos: $pos")
			}
		}
	}

	data class DeleteFile(val path: String) : Request {
		companion object {
			const val ID: UInt = 6u
		}
	}

	data class CreateFolder(val path: String) : Request {
		companion object {
			const val ID: UInt = 7u
		}
	}

	data class DeleteFolder(val path: String) : Request {
		companion object {
			const val ID: UInt = 8u
		}
	}

	data class ListFolder(val path: String) : Request {
		companion object {
			const val ID: UInt = 9u
		}
	}

	data class CopyFolder(val src: String, val dst: String) : Request {
		companion object {
			const val ID: UInt = 10u
		}
	}

	data class Stat(val path: String) : Request {
		companion object {
			const val ID: UInt = 11u
		}
	}

	data class Rename(val src: String, val dst: String) : Request {
		companion object {
			const val ID: UInt = 12u
		}
	}

	data class Symlink(val path: String, val link: String) : Request {
		companion object {
			const val ID: UInt = 13u
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

			is Request.Chmod -> {
				out.writeU32(Request.Chmod.ID)
				out.writeUtf8(request.path)
				out.writeArray(request.ops) { op ->
					out.writeBoolean(op.value)
					out.writeArray(op.bits) { bit ->
						out.writeU8(bit.pos)
					}
				}
			}

			is Request.DeleteFile -> {
				out.writeU32(Request.DeleteFile.ID)
				out.writeUtf8(request.path)
			}

			is Request.CreateFolder -> {
				out.writeU32(Request.CreateFolder.ID)
				out.writeUtf8(request.path)
			}

			is Request.DeleteFolder -> {
				out.writeU32(Request.DeleteFolder.ID)
				out.writeUtf8(request.path)
			}

			is Request.ListFolder -> {
				out.writeU32(Request.ListFolder.ID)
				out.writeUtf8(request.path)
			}

			is Request.CopyFolder -> {
				out.writeU32(Request.CopyFolder.ID)
				out.writeUtf8(request.src)
				out.writeUtf8(request.dst)
			}

			is Request.Stat -> {
				out.writeU32(Request.Stat.ID)
				out.writeUtf8(request.path)
			}

			is Request.Rename -> {
				out.writeU32(Request.Rename.ID)
				out.writeUtf8(request.src)
				out.writeUtf8(request.dst)
			}

			is Request.Symlink -> {
				out.writeU32(Request.Symlink.ID)
				out.writeUtf8(request.path)
				out.writeUtf8(request.link)
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

				Request.Chmod.ID -> Request.Chmod(
					path = input.readUtf8(),
					ops = input.readArray {
						Request.Chmod.Op(
							value = input.readBoolean(),
							bits = input.readArray {
								Request.Chmod.Bit[input.readU8()]
							}
						)
					}
				)

				Request.DeleteFile.ID -> Request.DeleteFile(
					path = input.readUtf8()
				)

				Request.CreateFolder.ID -> Request.CreateFolder(
					path = input.readUtf8()
				)

				Request.DeleteFolder.ID -> Request.DeleteFolder(
					path = input.readUtf8()
				)

				Request.ListFolder.ID -> Request.ListFolder(
					path = input.readUtf8()
				)

				Request.CopyFolder.ID -> Request.CopyFolder(
					src = input.readUtf8(),
					dst = input.readUtf8()
				)

				Request.Stat.ID -> Request.Stat(
					path = input.readUtf8()
				)

				Request.Rename.ID -> Request.Rename(
					src = input.readUtf8(),
					dst = input.readUtf8()
				)

				Request.Symlink.ID -> Request.Symlink(
					path = input.readUtf8(),
					link = input.readUtf8()
				)

				else -> throw NoSuchElementException("unrecognized response type id: $typeId")
			}

			return RequestEnvelope(id, request)
		}
	}
}
