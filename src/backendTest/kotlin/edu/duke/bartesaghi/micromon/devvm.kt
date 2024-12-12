package edu.duke.bartesaghi.micromon

import com.github.ajalt.mordant.TermColors
import io.kotest.common.runBlocking
import io.kotest.core.spec.Spec
import io.kotest.engine.TestEngineLauncher
import io.kotest.engine.launcher.*
import io.kotest.engine.listener.*
import io.kotest.framework.discovery.Discovery
import io.kotest.framework.discovery.DiscoveryRequest
import kotlin.reflect.KClass
import kotlin.system.exitProcess


/**
 * An entry point for running tests inside the dev VM
 */
fun main() {

	// run just one test, or one group of tests
	//return run(Test(TestTheThing::class, "group", "test"))

	// or, run a suite of tests
	//return run(Classes(TestOneThing::class, TestAnotherThing::class))

	// or, by default, run all the tests
	return run()
}


sealed interface TestsDescriptor

class Test(val c: KClass<out Spec>, vararg val path: String) : TestsDescriptor
class Classes(vararg val c: KClass<out Spec>) : TestsDescriptor
object All : TestsDescriptor


private fun run(desc: TestsDescriptor = All) {

	// build the kotest launcher
	var launcher = TestEngineLauncher(CompositeTestEngineListener(listOf(
		CollectingTestEngineListener(),
		LoggingTestEngineListener,
		ThreadSafeTestEngineListener(PinnedSpecTestEngineListener(EnhancedConsoleTestEngineListener(TermColors(TermColors.Level.TRUECOLOR)))),
	)))

	// apply the tests description to the launcher
	launcher = when(desc) {

		// run a single test
		is Test -> launcher
			.withExtensions(listOf(TestPathTestCaseFilter(desc.path.joinToString(" -- "), desc.c)))
			.withClasses(listOf(desc.c))

		is Classes -> launcher
			.withClasses(desc.c.toList())

		is All -> launcher.withClasses(run {
			val result = Discovery().discover(DiscoveryRequest())
			result.error?.let { throw it }
			result.specs
		})
	}

	// run the tests!
	launcher.launch()

	// looks like kotest leaves some threads running ... somewhere
	// so we need to hard exit here
	exitProcess(0)
}
