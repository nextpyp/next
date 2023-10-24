package edu.duke.bartesaghi.micromon


fun benchmark(numWarmups: Int, numRuns: Int, block: () -> Unit): BenchResults {

	// warmup
	for (i in 0 until numWarmups) {
		block()
	}

	// runs
	val startNs = System.nanoTime()
	for (i in 0 until numRuns) {
		block()
	}
	val elapsedNs = System.nanoTime() - startNs

	return BenchResults(numWarmups, numRuns, elapsedNs)
}


data class BenchResults(
	val numWarmups: Int,
	val numRuns: Int,
	val elapsedNs: Long
) {

	val elapsedS: Double get() =
		elapsedNs.toDouble()/1e9

	val ops: Double get() =
		numRuns.toDouble()/elapsedS

	override fun toString() =
		"Benchmark(%d runs in %.2f s => %.2f op/s)".format(numRuns, elapsedS, ops)
}
