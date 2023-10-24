package edu.duke.bartesaghi.micromon


// NOTE: not thread-safe, yet
class Profiler {

	val times = LinkedHashMap<String,Long>()
	val starts = HashMap<String,Long>()

	fun clear() {
		times.clear()
	}

	inline fun <R> time(name: String, block: () -> R): R {

		// run the block, time it
		val start = System.nanoTime()
		val out = block()
		val elapsed = System.nanoTime() - start

		val old = times[name]
		times[name] = if (old != null) {
			old + elapsed
		} else {
			elapsed
		}

		return out
	}

	fun start(name: String) {
		starts[name] = System.nanoTime()
	}

	fun stop(name: String) {
		val startNs = starts.remove(name) ?: return
		val elapsed = System.nanoTime() - startNs
		times[name] = (times[name] ?: 0) + elapsed
	}

	fun stopAll() {
		for (n in starts.keys.toList()) {
			stop(n)
		}
	}

	fun switch(name: String) {
		stopAll()
		start(name)
	}

	override fun toString() = StringBuilder().apply {
		append("Profile:")
		for ((name, ns) in times) {
			append("   ")
			append(name)
			append(' ')
			append(ns.toTimeString())
		}
	}.toString()

	private fun Long.toTimeString(): String =
		when {
			this > 1_000_000_000 -> "%.1f s".format(toDouble()/1e9)
			this > 1_000_000 -> "%.1f ms".format(toDouble()/1e6)
			this > 1_000 -> "%.1f us".format(toDouble()/1e3)
			else -> "$this ns"
		}
}
