package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.linux.hostprocessor.HostProcessor
import edu.duke.bartesaghi.micromon.linux.hostprocessor.Response
import edu.duke.bartesaghi.micromon.linux.hostprocessor.recv
import io.kotest.assertions.fail
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.div


@EnabledIf(RuntimeEnvironment.Host::class)
class TestHostProcessor : DescribeSpec({

	describe("host processor") {

		it("ping/pong") {
			withHostProcessor { hostProcessor ->
				hostProcessor.ping()
			}
		}

		it("exec") {
			withHostProcessor { hostProcessor ->
				val proc = hostProcessor.exec(Command("ls", "-al"))
				println("launched process: pid=${proc.pid}")
				proc.pid.shouldBeGreaterThan(0u)
			}
		}

		it("exec stream fin") {
			withHostProcessor { hostProcessor ->
				hostProcessor.execStream(Command("ls", "-al")).use { proc ->
					proc.pid.shouldBeGreaterThan(0u)
					val event = proc.recv<Response.ProcessEvent.Fin>()
					event.exitCode.shouldBe(0)
				}
			}
		}

		it("exec stream stdout") {
			withHostProcessor { hostProcessor ->
				hostProcessor.execStream(Command("echo", "-n", "foo"), stdout=true).use { proc ->
					proc.pid.shouldBeGreaterThan(0u)
					val console = proc.recv<Response.ProcessEvent.Console>()
					console.kind.shouldBe(Response.ProcessEvent.ConsoleKind.Stdout)
					console.chunk.toString(Charsets.UTF_8).shouldBe("foo")
					val fin = proc.recv<Response.ProcessEvent.Fin>()
					fin.exitCode.shouldBe(0)
				}
			}
		}

		it("exec stream stderr") {
			withHostProcessor { hostProcessor ->
				hostProcessor.execStream(Command("/bin/sh", "-c", "echo -n foo 1>&2"), stderr=true).use { proc ->
					proc.pid.shouldBeGreaterThan(0u)
					val console = proc.recv<Response.ProcessEvent.Console>()
					console.kind.shouldBe(Response.ProcessEvent.ConsoleKind.Stderr)
					console.chunk.toString(Charsets.UTF_8).shouldBe("foo")
					val fin = proc.recv<Response.ProcessEvent.Fin>()
					fin.exitCode.shouldBe(0)
				}
			}
		}

		it("exec stream stdin") {
			withHostProcessor { hostProcessor ->
				hostProcessor.execStream(Command("cat", "-"), stdin=true, stdout=true).use { proc ->
					proc.pid.shouldBeGreaterThan(0u)

					// write to stdin, then close
					proc.stdin.write("hi".toByteArray(Charsets.UTF_8))
					proc.stdin.close()

					val console = proc.recv<Response.ProcessEvent.Console>()
					console.kind.shouldBe(Response.ProcessEvent.ConsoleKind.Stdout)
					console.chunk.toString(Charsets.UTF_8).shouldBe("hi")
					val fin = proc.recv<Response.ProcessEvent.Fin>()
					fin.exitCode.shouldBe(0)
				}
			}
		}

		it("exec dir") {
			withHostProcessor { hostProcessor ->
				hostProcessor.execStream(Command("pwd"), dir=Paths.get("/tmp"), stdout=true).use { proc ->
					val console = proc.recv<Response.ProcessEvent.Console>()
					console.kind.shouldBe(Response.ProcessEvent.ConsoleKind.Stdout)
					console.chunk.toString(Charsets.UTF_8).shouldBe("/tmp\n")
					val fin = proc.recv<Response.ProcessEvent.Fin>()
					fin.exitCode.shouldBe(0)
				}
			}
		}

		it("exec multiplexing") {
			withHostProcessor { hostProcessor ->
				hostProcessor.execStream(Command("cat", "-"), stdin=true, stdout=true).use { proc1 ->
					hostProcessor.execStream(Command("cat", "-"), stdin=true, stdout=true).use { proc2 ->

						suspend fun echo(proc: HostProcessor.StreamingProcess, msg: String) {

							// write the msg to stdin
							proc.stdin.write(msg.toByteArray(Charsets.UTF_8))

							// wait for the echo on stdout
							val console = proc.recv<Response.ProcessEvent.Console>()
							console.kind.shouldBe(Response.ProcessEvent.ConsoleKind.Stdout)
							console.chunk.toString(Charsets.UTF_8).shouldBe(msg)
						}

						// send some traffic arbitrarily to both processes
						echo(proc1, "knock knock")
						echo(proc2, "who's there?")
						echo(proc1, "Interrupting cow")
						echo(proc2, "Interrup ...")
						echo(proc1, "MOO! 🐄")

						proc1.stdin.close()
						var fin = proc1.recv<Response.ProcessEvent.Fin>()
						fin.exitCode.shouldBe(0)

						proc2.stdin.close()
						fin = proc2.recv<Response.ProcessEvent.Fin>()
						fin.exitCode.shouldBe(0)
					}
				}
			}
		}

		it("status") {
			withHostProcessor { hostProcessor ->

				// start a process we can end by closing stdin
				hostProcessor.execStream(Command("cat", "-"), stdin=true).use { proc ->

					var isRunning = proc.status()
					isRunning.shouldBe(true)

					// stop it and give the proccess a bit of time to exit
					proc.stdin.close()
					delay(200)

					isRunning = proc.status()
					isRunning.shouldBe(false)
				}
			}
		}

		it("kill") {
			withHostProcessor { hostProcessor ->

				// start a process we can end by closing stdin
				hostProcessor.execStream(Command("cat", "-"), stdin=true).use { proc ->

					var isRunning = proc.status()
					isRunning.shouldBe(true)

					// kill it with fire
					proc.kill()
					delay(200)

					isRunning = proc.status()
					isRunning.shouldBe(false)
				}
			}
		}

		it("username") {
			withHostProcessor { hostProcessor ->
				hostProcessor.username(0u).shouldBe("root")
				hostProcessor.username(UInt.MAX_VALUE - 5u).shouldBeNull() // probably?
			}
		}

		it("uid") {
			withHostProcessor { hostProcessor ->
				hostProcessor.uid("root").shouldBe(0u)
				hostProcessor.uid("probably not a user you have, right?").shouldBeNull()
			}
		}

		it("groupname") {
			withHostProcessor { hostProcessor ->
				hostProcessor.groupname(1u).shouldBe("daemon")
				hostProcessor.groupname(UInt.MAX_VALUE - 5u).shouldBeNull() // probably?
			}
		}

		it("gid") {
			withHostProcessor { hostProcessor ->
				hostProcessor.gid("daemon").shouldBe(1u)
				hostProcessor.gid("probably not a group you have, right?").shouldBeNull()
			}
		}

		it("gids") {
			withHostProcessor { hostProcessor ->
				hostProcessor.gids(0u).shouldBe(listOf(0u))
				hostProcessor.gids(UInt.MAX_VALUE - 5u).shouldBeNull() // probably?
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
			withTimeoutOrNull(2_000) {
				block(hostProcessor)
			} ?: fail("Test timed out, probably waiting for the host processor")
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

	override suspend fun closeAll() {

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

		process.exitValue().shouldBe(0)
	}
}
