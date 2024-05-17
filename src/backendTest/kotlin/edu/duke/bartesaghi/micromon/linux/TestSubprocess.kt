package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.linux.hostprocessor.HostProcessor
import edu.duke.bartesaghi.micromon.linux.subprocess.SubprocessClient
import io.kotest.assertions.fail
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.io.path.writeBytes


@EnabledIf(RuntimeEnvironment.Website::class)
class TestSubprocess : DescribeSpec({

	// NOTE: all this IPC stuff has a TON of potential for weird concurrency bugs!
	//       so run all the tests a bunch of times and hope we find a bug
	val testCount = 10

	describe("subprocess") {

		it("ping/pong").config(invocations = testCount) {
			withSubprocess { _, client ->
				client.ping()
			}
		}

		it("usernames").config(invocations = testCount) {
			withSubprocess { hostProcessor, client ->
				val uids = client.uids()
				val exp = System.getProperty("user.name")
				hostProcessor.username(uids.uid).shouldBe(exp)
				hostProcessor.username(uids.euid).shouldBe(exp)
			}
		}

		it("read file, small").config(invocations = testCount) {
			withSubprocess { _, client ->
				TempFile().use { file ->

					// write a small file
					val msg = "hello"
					file.path.writeString(msg)

					// read it
					val buf = client.readFile(file.path)
					buf.toString(Charsets.UTF_8).shouldBe(msg)
				}
			}
		}

		it("read file, large").config(invocations = testCount) {
			withSubprocess { _, client ->
				TempFile().use { file ->

					// write a large file with structured, but non-trivial, content
					val content = ByteArray(16*1024*1024 - 16)
					for (i in content.indices) {
						content[i] = i.toUByte().toByte()
					}
					file.path.writeBytes(content)

					// read it
					val buf = client.readFile(file.path)
					buf.shouldBe(content)
				}
			}
		}

		it("write file, small").config(invocations = testCount) {
			withSubprocess { _, client ->
				TempFile().use { file ->

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
			withSubprocess { _, client ->
				TempFile().use { file ->

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
	}
})


suspend fun withSubprocess(block: suspend (HostProcessor, SubprocessClient) -> Unit) {
	HostProcessor().use { hostProcessor ->
		SubprocessClient.start("Test", 64, 2000, hostProcessor)
			.use { client ->
				withTimeoutOrNull(2000) {
					block(hostProcessor, client)
				} ?: fail("Timed out")
			}
	}
}


class TempFile : AutoCloseable {

	val path = Files.createTempFile("nextpyp-subprocess", null)

	override fun close() {
		path.delete()
	}
}
