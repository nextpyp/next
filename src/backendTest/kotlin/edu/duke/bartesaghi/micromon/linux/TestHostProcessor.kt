package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.SuspendCloseable
import edu.duke.bartesaghi.micromon.exists
import edu.duke.bartesaghi.micromon.slowIOs
import edu.duke.bartesaghi.micromon.use
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.div


class TestHostProcessor : DescribeSpec({

	describe("host processor") {

		it("ping/pong") {
			withHostProcessor { hostProcessor ->
				withTimeout(500) {
					hostProcessor.ping()
				}
			}
		}

		it("exec") {
			withHostProcessor { hostProcessor ->
				withTimeout(500) {
					val proc = hostProcessor.exec("ls", listOf("-al"))
					println("launched process: pid=${proc.pid}")
					proc.pid.shouldBeGreaterThan(0u)
				}
			}
		}

		it("exec stream fin") {
			withHostProcessor { hostProcessor ->
				withTimeout(500) {
					hostProcessor.execStream("ls", listOf("-al")).use { proc ->
						proc.pid.shouldBeGreaterThan(0u)
						val event = proc.recv<Response.ProcessEvent.Fin>()
						event.exitCode.shouldBe(0)
					}
				}
			}
		}

		it("exec stream stdout") {
			withHostProcessor { hostProcessor ->
				withTimeout(2_000) {
					hostProcessor.execStream("echo", listOf("foo"), stdout=true).use { proc ->
						proc.pid.shouldBeGreaterThan(0u)
						println("waiting for console ...") // TEMP
						val console = proc.recv<Response.ProcessEvent.Console>()
						println("console: $console") // TEMP
						console.kind.shouldBe(Response.ProcessEvent.ConsoleKind.Stdout)
						console.chunk.toString(Charsets.UTF_8).shouldBe("foo")
						println("waiting for fin ...") // TEMP
						val fin = proc.recv<Response.ProcessEvent.Fin>()
						println("fin: $fin") // TEMP
						fin.exitCode.shouldBe(0)
					}
				}
			}
		}
	}
})


suspend fun withHostProcessor(block: suspend (HostProcessor) -> Unit) {

	// pick a socket dir for testing
	val socketDir = Paths.get("/tmp/nextpyp-host-processor")

	HostProcessorProcess(socketDir).use { process ->

		// wait a little bit for the socket file to show up
		val socketPath = socketDir / "host-processor-${process.pid}"
		for (i in 0 until 10) {
			if (socketPath.exists()) {
				println("socket file availabile at: $socketPath")
				break
			}
			delay(100)
		}
		if (!socketPath.exists()) {
			throw Error("socket file was not created at: $socketPath")
		}

		HostProcessor(socketDir, process.pid).use { hostProcessor ->
			block(hostProcessor)
		}
	}
}


class HostProcessorProcess(socketDir: Path) : SuspendCloseable {

	private val process = run {

		// start a host processor instance
		val hpexec = Paths.get("run/host-processor")
		if (!hpexec.exists()) {
			throw Error("can't find host processor executable at: $hpexec")
		}
		val process = ProcessBuilder()
			.command(hpexec.toString(), "--log", "host_processor=trace", socketDir.toString())
			.redirectOutput(ProcessBuilder.Redirect.INHERIT)
			.redirectError(ProcessBuilder.Redirect.INHERIT)
			.start()

		println("host processor launched")

		process
	}

	val pid: Long get() =
		process.pid()

	override suspend fun close() {
		// cleanup the host processor
		if (process.isAlive) {
			println("cleaning up host processor")
			process.destroy() // sends SIGTERM
			slowIOs {
				process.waitFor(2, TimeUnit.SECONDS)
			}
			println("host processor finished")
		} else {
			println("host processor already exited")
		}
	}
}
