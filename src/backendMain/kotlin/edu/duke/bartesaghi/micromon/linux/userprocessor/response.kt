package edu.duke.bartesaghi.micromon.linux.userprocessor

import edu.duke.bartesaghi.micromon.linux.hostprocessor.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream


sealed interface Response {

	data class Error(val reason: String) : Response {
		companion object {
			const val ID: UInt = 1u
		}
	}

	object Pong : Response {
		const val ID: UInt = 2u
	}

	data class Uids(val uid: UInt, val euid: UInt, val suid: UInt) : Response {
		companion object {
			const val ID: UInt = 3u
		}
	}

	data class ReadFile(val response: Response) : Response {
		companion object {
			const val ID: UInt = 4u
		}

		sealed interface Response

		data class Open(
			val bytes: ULong
		) : Response {
			companion object {
				const val ID: UInt = 1u
			}
		}

		class Chunk(
			val sequence: UInt,
			val data: ByteArray
		) : Response {
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
		) : Response {
			companion object {
				const val ID: UInt = 3u
			}
		}
	}

	data class WriteFile(val response: Response) : Response {
		companion object {
			const val ID: UInt = 5u
		}

		sealed interface Response

		object Opened : Response {
			const val ID: UInt = 1u
		}

		object Closed : Response {
			const val ID: UInt = 2u
		}
	}

	object Chmod : Response {
		const val ID: UInt = 6u
	}

	object DeleteFile : Response {
		const val ID: UInt = 7u
	}

	object CreateFolder : Response {
		const val ID: UInt = 8u
	}

	object DeleteFolder : Response {
		const val ID: UInt = 9u
	}

	object CopyFolder : Response {
		const val ID: UInt = 10u
	}

	data class Stat(val response: Response) : Response {
		companion object {
			const val ID: UInt = 11u
		}

		sealed interface Response

		object NotFound : Response {
			const val ID: UInt = 1u
		}

		data class File(val size: ULong) : Response {
			companion object {
				const val ID: UInt = 2u
			}
		}

		object Dir : Response {
			const val ID: UInt = 3u
		}

		data class Symlink(val response: Response) : Response {
			companion object {
				const val ID: UInt = 4u
			}

			sealed interface Response

			object NotFound : Response {
				const val ID: UInt = 1u
			}

			data class File(val size: ULong) : Response {
				companion object {
					const val ID: UInt = 2u
				}
			}

			object Dir : Response {
				const val ID: UInt = 3u
			}

			object Other : Response {
				const val ID: UInt = 4u
			}
		}

		object Other : Response {
			const val ID: UInt = 5u
		}
	}

	object Rename : Response {
		const val ID: UInt = 12u
	}

	object Symlink : Response {
		const val ID: UInt = 13u
	}
}

fun Response.ReadFile.Response.into(): Response =
	Response.ReadFile(this)

fun Response.WriteFile.Response.into(): Response =
	Response.WriteFile(this)

fun Response.Stat.Response.into(): Response =
	Response.Stat(this)


inline fun <reified T:Response> Response.cast(): T {
	return when (this) {
		is Response.Error -> throw ErrorResponseException(reason)
		is T -> this // ok
		else -> throw UnexpectedResponseException(toString())
	}
}

inline fun <reified T:Response.ReadFile.Response> Response.ReadFile.Response.cast(): T {
	return when (this) {
		is T -> this // ok
		else -> throw UnexpectedResponseException(toString())
	}
}

inline fun <reified T:Response.WriteFile.Response> Response.WriteFile.Response.cast(): T {
	return when (this) {
		is T -> this // ok
		else -> throw UnexpectedResponseException(toString())
	}
}

inline fun <reified T:Response.Stat.Response> Response.Stat.Response.cast(): T {
	return when (this) {
		is T -> this // ok
		else -> throw UnexpectedResponseException(toString())
	}
}

class UnexpectedResponseException(val response: String) : RuntimeException("Unexpected response: $response")
class ErrorResponseException(val reason: String) : RuntimeException("Server error: $reason")


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
				out.writeU32(response.suid)
			}

			is Response.ReadFile -> {
				out.writeU32(Response.ReadFile.ID)
				when (val response = response.response) {

					is Response.ReadFile.Open -> {
						out.writeU32(Response.ReadFile.Open.ID)
						out.writeU64(response.bytes)
					}

					is Response.ReadFile.Chunk -> {
						out.writeU32(Response.ReadFile.Chunk.ID)
						out.writeU32(response.sequence)
						out.writeBytes(response.data)
					}

					is Response.ReadFile.Close -> {
						out.writeU32(Response.ReadFile.Close.ID)
						out.writeU32(response.sequence)
					}
				}
			}

			is Response.WriteFile -> {
				out.writeU32(Response.WriteFile.ID)
				when (response.response) {

					is Response.WriteFile.Opened -> {
						out.writeU32(Response.WriteFile.Opened.ID)
					}

					is Response.WriteFile.Closed -> {
						out.writeU32(Response.WriteFile.Closed.ID)
					}
				}
			}

			is Response.Chmod -> {
				out.writeU32(Response.Chmod.ID)
			}

			is Response.DeleteFile -> {
				out.writeU32(Response.DeleteFile.ID)
			}

			is Response.CreateFolder -> {
				out.writeU32(Response.CreateFolder.ID)
			}

			is Response.DeleteFolder -> {
				out.writeU32(Response.DeleteFolder.ID)
			}

			is Response.CopyFolder -> {
				out.writeU32(Response.CopyFolder.ID)
			}

			is Response.Stat -> {
				out.writeU32(Response.Stat.ID)
				when (response.response) {

					is Response.Stat.NotFound -> {
						out.writeU32(Response.Stat.NotFound.ID)
					}

					is Response.Stat.File -> {
						out.writeU32(Response.Stat.File.ID)
						out.writeU64(response.response.size)
					}

					is Response.Stat.Dir -> {
						out.writeU32(Response.Stat.Dir.ID)
					}

					is Response.Stat.Symlink -> {
						out.writeU32(Response.Stat.Symlink.ID)
						when (response.response.response) {

							is Response.Stat.Symlink.NotFound -> {
								out.writeU32(Response.Stat.Symlink.NotFound.ID)
							}

							is Response.Stat.Symlink.File -> {
								out.writeU32(Response.Stat.Symlink.File.ID)
								out.writeU64(response.response.response.size)
							}

							is Response.Stat.Symlink.Dir -> {
								out.writeU32(Response.Stat.Symlink.Dir.ID)
							}

							is Response.Stat.Symlink.Other -> {
								out.writeU32(Response.Stat.Symlink.Other.ID)
							}
						}
					}

					is Response.Stat.Other -> {
						out.writeU32(Response.Stat.Other.ID)
					}
				}
			}

			is Response.Rename -> {
				out.writeU32(Response.Rename.ID)
			}

			is Response.Symlink -> {
				out.writeU32(Response.Symlink.ID)
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

				Response.Pong.ID -> Response.Pong

				Response.Uids.ID -> Response.Uids(
					uid = input.readU32(),
					euid = input.readU32(),
					suid = input.readU32()
				)

				Response.ReadFile.ID -> Response.ReadFile(run {
					when (val readFileResponseTypeId = input.readU32()) {

						Response.ReadFile.Open.ID -> Response.ReadFile.Open(
							bytes = input.readU64()
						)

						Response.ReadFile.Chunk.ID -> Response.ReadFile.Chunk(
							sequence = input.readU32(),
							data = input.readBytes()
						)

						Response.ReadFile.Close.ID -> Response.ReadFile.Close(
							sequence = input.readU32()
						)

						else -> throw NoSuchElementException("unrecognized read file response type id: $readFileResponseTypeId")
					}
				})

				Response.WriteFile.ID -> Response.WriteFile(run {
					when (val writeFileResponseTypeId = input.readU32()) {
						Response.WriteFile.Opened.ID -> Response.WriteFile.Opened
						Response.WriteFile.Closed.ID -> Response.WriteFile.Closed
						else -> throw NoSuchElementException("unrecognized write file response type id: $writeFileResponseTypeId")
					}
				})

				Response.Chmod.ID -> Response.Chmod

				Response.DeleteFile.ID -> Response.DeleteFile

				Response.CreateFolder.ID -> Response.CreateFolder

				Response.DeleteFolder.ID -> Response.DeleteFolder

				Response.CopyFolder.ID -> Response.CopyFolder

				Response.Stat.ID -> Response.Stat(run {
					when (val lstatTypeId = input.readU32()) {
						Response.Stat.NotFound.ID -> Response.Stat.NotFound
						Response.Stat.File.ID -> Response.Stat.File(
							size = input.readU64()
						)
						Response.Stat.Dir.ID -> Response.Stat.Dir
						Response.Stat.Symlink.ID -> Response.Stat.Symlink(run {
							when (val statTypeId = input.readU32()) {
								Response.Stat.Symlink.NotFound.ID -> Response.Stat.Symlink.NotFound
								Response.Stat.Symlink.File.ID -> Response.Stat.Symlink.File(
									size = input.readU64()
								)
								Response.Stat.Symlink.Dir.ID -> Response.Stat.Symlink.Dir
								Response.Stat.Symlink.Other.ID -> Response.Stat.Symlink.Other
								else -> throw NoSuchElementException("unrecognized stat type: $statTypeId")
							}
						})
						Response.Stat.Other.ID -> Response.Stat.Other
						else -> throw NoSuchElementException("unrecognized lstat type: $lstatTypeId")
					}
				})

				Response.Rename.ID -> Response.Rename

				Response.Symlink.ID -> Response.Symlink

				else -> throw NoSuchElementException("unrecognized response type: $responseTypeId")
			}

			return ResponseEnvelope(id, response)
		}
	}
}


data class FileEntry(
	val name: String,
	val kind: Kind
) {

	enum class Kind(val id: UByte) {

		Unknown(0u),
		File(1u),
		Dir(2u),
		Symlink(3u),
		Fifo(4u),
		Socket(5u),
		BlockDev(6u),
		CharDev(7u);

		companion object {

			operator fun get(id: UByte): Kind =
				values()
					.find { it.id == id }
					?: Unknown
		}
	}

	companion object {

		private const val LIST_EOF = UByte.MAX_VALUE

		fun reader(buf: ByteArray): Sequence<FileEntry> {
			val input = DataInputStream(ByteArrayInputStream(buf))
			return generateSequence {
				when (val kindId = input.readU8()) {
					LIST_EOF -> null
					else -> FileEntry(
						name = input.readUtf8(),
						kind = Kind[kindId]
					)
				}
			}
		}
	}
}
