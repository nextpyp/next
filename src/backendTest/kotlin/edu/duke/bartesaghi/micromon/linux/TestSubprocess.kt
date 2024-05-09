package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.linux.subprocess.SubprocessClient
import io.kotest.assertions.fail
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import kotlin.io.path.writeBytes


@EnabledIf(RuntimeEnvironment.Website::class)
class TestSubprocess : DescribeSpec({

	describe("subprocess") {

		it("ping/pong") {
			withSubprocess { client ->
				client.ping()
			}
		}

		it("read file, small") {
			withSubprocess { client ->
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

		it("read file, large") {
			withSubprocess { client ->
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

		it("write file, small") {
			withSubprocess { client ->
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

		it("write file, large") {
			withSubprocess { client ->
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


suspend fun withSubprocess(block: suspend (SubprocessClient) -> Unit) {
	SubprocessClient.start(Paths.get("/tmp/nextpyp-subprocess"), "Test", 64, Duration.ofMillis(500))
		.use { client ->
			withTimeoutOrNull(2000) {
				block(client)
			} ?: fail("Timed out")
		}
}


class TempFile : AutoCloseable {

	val path = Files.createTempFile("nextpyp-subprocess", null)

	override fun close() {
		path.delete()
	}
}
