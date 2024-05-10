package edu.duke.bartesaghi.micromon

import kotlin.reflect.KClass


/**
 * An entry point for running tests inside the dev VM
 */
fun main() {

	// by default, run all the tests
	@Suppress("CanBeVal")
	var test: Test? = null

	// or, focus on just one test
	//test = Test(TestSubprocess::class, listOf("subprocess", "ping/pong"))

	@Suppress("KotlinConstantConditions")
	run(test)
}


private data class Test(
	val c: KClass<*>,
	val path: List<String>? = null
) {
	fun args(): Array<String> =
		arrayOf(
			"--spec", c.qualifiedName!!,
			*if (path != null) {
				arrayOf("--testpath", path.joinToString(" -- "))
			} else {
				emptyArray()
			}
		)
}


private fun run(test: Test? = null) {

	/**
	 * Run tests using kotest's console launcher
	 *
	 * for docs on the launcher args, see:
	 * [io.kotest.engine.launcher.LauncherArgs]
	 */
	@Suppress("OPT_IN_USAGE") // ok, we opted in
	io.kotest.engine.launcher.main(arrayOf(
		//"--listener", "teamcity" // IntelliJ integration doesn't seem to work inside the dev VM and/or container ;_;
		"--listener", "enhanced",
		"--termcolor", "true",
		*(test?.args() ?: emptyArray())
	))
}
