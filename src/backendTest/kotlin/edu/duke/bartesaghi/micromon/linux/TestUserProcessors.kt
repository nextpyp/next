package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.RuntimeEnvironment
import edu.duke.bartesaghi.micromon.linux.hostprocessor.HostProcessor
import edu.duke.bartesaghi.micromon.linux.userprocessor.UserProcessorException
import edu.duke.bartesaghi.micromon.linux.userprocessor.UserProcessors
import edu.duke.bartesaghi.micromon.use
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeoutOrNull


/**
 * These tests require the `user-processor` exectuable to be correctly configured for the `tester` user
 */
@EnabledIf(RuntimeEnvironment.Website::class)
class TestUserProcessors : DescribeSpec({

	val username = "tester"

	describe("user processors") {

		it("no exec") {
			withSubprocesses { _, subprocesses ->
				shouldThrow<UserProcessorException> {
					subprocesses.get("not configured for runas") // probably
				}
			}
		}

		it("usernames") {
			withSubprocesses { hostProcessor, subprocesses ->
				val uids = subprocesses
					.get(username)
					.uids()
				hostProcessor.username(uids.uid).shouldBe(System.getProperty("user.name"))
				hostProcessor.username(uids.euid).shouldBe(username)
			}
		}
	}
})


suspend fun withSubprocesses(block: suspend (HostProcessor, UserProcessors) -> Unit) {
	HostProcessor().use { hostProcessor ->
		UserProcessors(hostProcessor).use { subprocesses ->
			withTimeoutOrNull(2000) {
				block(hostProcessor, subprocesses)
			} ?: fail("Timed out")
		}
	}
}
