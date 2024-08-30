package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.linux.hostprocessor.HostProcessor
import edu.duke.bartesaghi.micromon.linux.userprocessor.*
import io.kotest.assertions.fail
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.div
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.writeBytes


/**
 * These tests require the `user-processor` exectuable to be correctly configured for the `tester` user
 */
@EnabledIf(RuntimeEnvironment.Website::class)
class TestUserProcessor : DescribeSpec({

	describe("protocol") {

		it("request") {

			fun roundtrip(request: Request) {
				val src = RequestEnvelope(5u, request)
				val dst = RequestEnvelope.decode(src.encode())
				dst.requestId.shouldBe(src.requestId)
				dst.request.shouldBe(src.request)
			}

			roundtrip(Request.Ping)

			roundtrip(Request.Uids)

			roundtrip(Request.ReadFile("path"))

			roundtrip(Request.WriteFile.Open("path", true).into())
			roundtrip(Request.WriteFile.Chunk(5u, byteArrayOf(1, 2, 3)).into())
			roundtrip(Request.WriteFile.Close(42u).into())

			roundtrip(Request.Chmod("path", listOf()))
			roundtrip(Request.Chmod("path", listOf(
				Request.Chmod.Op(true, listOf(
					Request.Chmod.Bit.OtherExecute,
					Request.Chmod.Bit.OtherWrite,
					Request.Chmod.Bit.OtherRead,
				)),
				Request.Chmod.Op(false, listOf(
					Request.Chmod.Bit.GroupExecute,
					Request.Chmod.Bit.GroupWrite,
					Request.Chmod.Bit.GroupRead,
				)),
				Request.Chmod.Op(true, listOf(
					Request.Chmod.Bit.UserExecute,
					Request.Chmod.Bit.UserWrite,
					Request.Chmod.Bit.UserRead,
				)),
				Request.Chmod.Op(false, listOf(
					Request.Chmod.Bit.SetUid,
					Request.Chmod.Bit.SetGid,
					Request.Chmod.Bit.Sticky,
				))
			)))

			roundtrip(Request.DeleteFile("path"))
			roundtrip(Request.CreateFolder("path"))
			roundtrip(Request.DeleteFolder("path"))
			roundtrip(Request.ListFolder("path"))
			roundtrip(Request.CopyFolder("src", "dst"))
			roundtrip(Request.Stat("path"))
			roundtrip(Request.Rename("foo", "bar"))
			roundtrip(Request.Symlink("cow", "moo"))
		}

		it("response") {

			fun roundtrip(response: Response) {
				val src = ResponseEnvelope(5u, response)
				val dst = ResponseEnvelope.decode(src.encode())
				dst.requestId.shouldBe(src.requestId)
				dst.response.shouldBe(src.response)
			}

			roundtrip(Response.Pong)

			roundtrip(Response.Uids(5u, 42u, 7u))

			roundtrip(Response.ReadFile.Open(5u).into())
			roundtrip(Response.ReadFile.Chunk(5u, byteArrayOf(1, 2, 3)).into())
			roundtrip(Response.ReadFile.Close(42u).into())

			roundtrip(Response.WriteFile.Opened.into())
			roundtrip(Response.WriteFile.Closed.into())

			roundtrip(Response.Chmod)

			roundtrip(Response.DeleteFile)
			roundtrip(Response.CreateFolder)
			roundtrip(Response.DeleteFolder)
			roundtrip(Response.CopyFolder)

			roundtrip(Response.Stat.NotFound.into())
			roundtrip(Response.Stat.File(5u).into())
			roundtrip(Response.Stat.Dir.into())
			roundtrip(Response.Stat.Symlink(Response.Stat.Symlink.NotFound).into())
			roundtrip(Response.Stat.Symlink(Response.Stat.Symlink.File(42u)).into())
			roundtrip(Response.Stat.Symlink(Response.Stat.Symlink.Dir).into())
			roundtrip(Response.Stat.Symlink(Response.Stat.Symlink.Other).into())
			roundtrip(Response.Stat.Other.into())

			roundtrip(Response.Rename)
			roundtrip(Response.Symlink)
		}
	}


	val username = "tester"

	// NOTE: all this IPC stuff has a TON of potential for weird concurrency bugs!
	//       so run all the tests a bunch of times and hope we find a bug
	val testCount = 10

	describe("user processor") {

		it("ping/pong").config(invocations = testCount) {
			withUserProcessor(username) { _, client ->
				client.ping()
			}
		}

		it("usernames").config(invocations = testCount) {
			withUserProcessor(username) { hostProcessor, client ->
				val uids = client.uids()
				hostProcessor.username(uids.uid).shouldBe(username)
				hostProcessor.username(uids.euid).shouldBe(username)
				hostProcessor.username(uids.suid).shouldBe(System.getProperty("user.name"))
			}
		}

		it("read file, small").config(invocations = testCount) {
			withUserProcessor(username) { _, client ->
				TempFile().use { file ->

					// write a small file
					val msg = "hello"
					file.path.writeString(msg)
					file.path.editPermissions {
						add(PosixFilePermission.GROUP_READ)
						add(PosixFilePermission.OTHERS_READ)
					}

					// read it
					val buf = client.readFile(file.path)
					buf.toString(Charsets.UTF_8).shouldBe(msg)
				}
			}
		}

		it("read file, large").config(invocations = testCount) {
			withUserProcessor(username) { _, client ->
				TempFile().use { file ->

					// write a large file with structured, but non-trivial, content
					val content = ByteArray(16*1024*1024 - 16)
					for (i in content.indices) {
						content[i] = i.toUByte().toByte()
					}
					file.path.writeBytes(content)
					file.path.editPermissions {
						add(PosixFilePermission.GROUP_READ)
						add(PosixFilePermission.OTHERS_READ)
					}

					// read it
					val buf = client.readFile(file.path)
					buf.shouldBe(content)
				}
			}
		}

		it("write file, small").config(invocations = testCount) {
			withUserProcessor(username) { _, client ->
				TempFile().use { file ->

					file.path.editPermissions {
						add(PosixFilePermission.GROUP_WRITE)
						add(PosixFilePermission.OTHERS_WRITE)
					}

					// write a small file
					val msg = "hello"
					client.writeFile(file.path)
						.use { writer ->
							writer.write(msg.toByteArray(Charsets.UTF_8))
						}

					// read it
					file.path.readString().shouldBe(msg)
				}
			}
		}

		it("write file, large").config(invocations = testCount) {
			withUserProcessor(username) { _, client ->
				TempFile().use { file ->

					file.path.editPermissions {
						add(PosixFilePermission.GROUP_WRITE)
						add(PosixFilePermission.OTHERS_WRITE)
					}

					// write a large file with structured, but non-trivial, content
					val content = ByteArray(16*1024*1024 - 16)
					for (i in content.indices) {
						content[i] = i.toUByte().toByte()
					}
					client.writeFile(file.path)
						.use { writer ->
							writer.write(content)
						}

					// read it
					file.path.readBytes().shouldBe(content)
				}
			}
		}

		it("delete file").config(invocations = testCount) {
			withUserProcessor(username) { _, client ->

				// NOTE: can't use TempFile here, since the tester user can't delete it
				val path = Paths.get("/tmp/nextpyp-user-processor-delete-file-test")

				// so just write a file as the user
				client.writeFile(path).use { writer ->
					writer.writeAll(byteArrayOf(1, 2, 3))
				}

				path.exists().shouldBe(true)

				client.deleteFile(path)

				path.exists().shouldBe(false)
			}
		}

		it("chmod").config(invocations = testCount) {
			withUserProcessor(username) { _, client ->

				// NOTE: can't use TempFile here, since the tester user can't chmod it
				val path = Paths.get("/tmp/nextpyp-user-processor-chmod-test")

				// so just write a file as the user
				client.writeFile(path).use { writer ->
					writer.writeAll(byteArrayOf(1, 2, 3))
				}

				try {
					path.getPosixFilePermissions().shouldContainExactlyInAnyOrder(
						PosixFilePermission.OWNER_READ,
						PosixFilePermission.OWNER_WRITE,
						PosixFilePermission.GROUP_READ
					)

					client.chmod(path, listOf(
						Request.Chmod.Op(true, listOf(
							Request.Chmod.Bit.GroupWrite
						))
					))

					path.getPosixFilePermissions().shouldContainExactlyInAnyOrder(
						PosixFilePermission.OWNER_READ,
						PosixFilePermission.OWNER_WRITE,
						PosixFilePermission.GROUP_READ,
						PosixFilePermission.GROUP_WRITE
					)

					client.chmod(path, listOf(
						Request.Chmod.Op(false, listOf(
							Request.Chmod.Bit.GroupRead,
							Request.Chmod.Bit.GroupWrite,
						)),
						Request.Chmod.Op(true, listOf(
							Request.Chmod.Bit.OtherRead,
							Request.Chmod.Bit.OtherWrite,
						))
					))

					path.getPosixFilePermissions().shouldContainExactlyInAnyOrder(
						PosixFilePermission.OWNER_READ,
						PosixFilePermission.OWNER_WRITE,
						PosixFilePermission.OTHERS_READ,
						PosixFilePermission.OTHERS_WRITE
					)
				} finally {
					client.deleteFile(path)
				}
			}
		}

		it("create,delete folder").config(invocations = testCount) {
			withUserProcessor(username) { _, client ->

				val path = Paths.get("/tmp/nextpyp-user-processor-folder-test")

				path.exists().shouldBe(false)

				client.createFolder(path)

				path.exists().shouldBe(true)

				// put some files in there too
				client.writeFile(path.resolve("file"))
					.use { writer ->
						writer.writeAll(byteArrayOf(1, 2, 3))
					}

				client.deleteFolder(path)

				path.exists().shouldBe(false)
			}
		}

		it("list folder").config(invocations = testCount) {
			withUserProcessor(username) { _, client ->

				val path = Paths.get("/tmp/nextpyp-user-processor-list-folder-test")

				// create a folder with some files
				client.createFolder(path)
				client.writeFile(path.resolve("file"))
					.use { writer ->
						writer.writeAll(byteArrayOf(1, 2, 3))
					}

				val entries = client.listFolder(path)
					.toList()

				entries.size.shouldBe(1)
				entries[0].apply {
					name.shouldBe("file")
					kind.shouldBe(FileEntry.Kind.File)
				}

				client.deleteFolder(path)
			}
		}

		it("copy folder").config(invocations = testCount) {
			withUserProcessor(username) { _, client ->

				// create a folder to copy, with stuff in it
				val src = Paths.get("/tmp/nextpyp-user-processor-folder-src-test")
				client.createFolder(src)
				client.writeFile(src.resolve("file"))
					.use { writer ->
						writer.writeAll(byteArrayOf(1, 2, 3))
					}

				val dst = Paths.get("/tmp/nextpyp-user-processor-folder-dst-test")

				client.copyFolder(src, dst)

				dst.exists().shouldBe(true)
				val dstFile = dst / "file"
				dstFile.exists().shouldBe(true)
				client.readFile(dstFile).shouldBe(byteArrayOf(1, 2, 3))
			}
		}

		it("stat").config(invocations = testCount) {
			withUserProcessor(username) { _, client ->
				TempFile().use { file ->

					// make a file we can stat
					val msg = "hello"
					file.path.writeString(msg)
					file.path.editPermissions {
						add(PosixFilePermission.GROUP_READ)
						add(PosixFilePermission.OTHERS_READ)
					}

					// stat it
					val stat = client.stat(file.path)
					stat.shouldBe(Response.Stat.File(msg.length.toULong()))
				}
			}
		}

		it("rename").config(invocations = testCount) {
			withUserProcessor(username) { _, client ->

				// NOTE: can't use TempFile here, since the tester user can't move it
				val src = Paths.get("/tmp/nextpyp-user-processor-rename-test")
				val dst = Paths.get("/tmp/nextpyp-user-processor-renamed")

				// so just write a file as the user
				client.writeFile(src).use { writer ->
					writer.writeAll(byteArrayOf(1, 2, 3))
				}

				src.exists().shouldBe(true)
				dst.exists().shouldBe(false)

				client.rename(src, dst)

				src.exists().shouldBe(false)
				dst.exists().shouldBe(true)

				client.deleteFile(dst)
			}
		}

		it("symlink").config(invocations = testCount) {
			withUserProcessor(username) { _, client ->
				TempFile().use { file ->

					val link = file.path.parent / "linked"

					suspend fun linkExists(): Boolean {

						// NOTE: the simple existence check doesn't work for some reason
						//       symlinks and multiple file owners are weird I guess
						//link.exists().shouldBe(true)

						// so do something fancier
						return file.path.parent.listFolderFast()
							?.any { it.type == Filesystem.File.Type.Lnk && it.name == link.fileName.toString() }
							?: false
					}

					// just in case a failed test didn't cleanup
					if (linkExists()) {
						client.deleteFile(link)
					}

					linkExists().shouldBe(false)

					client.symlink(file.path, link)

					linkExists().shouldBe(true)

					client.deleteFile(link)
				}
			}
		}
	}
})


suspend fun withUserProcessor(username: String, block: suspend (HostProcessor, UserProcessor) -> Unit) {
	HostProcessor().use { hostProcessor ->
		UserProcessor.start(hostProcessor, username, 2000, "user_processor=trace")
			.use { client ->
				withTimeoutOrNull(2000) {
					block(hostProcessor, client)
				} ?: fail("Timed out")
			}
	}
}


class TempFile : AutoCloseable {

	val path = Files.createTempFile("nextpyp-user-processor", null)

	override fun close() {
		path.delete()
	}
}
