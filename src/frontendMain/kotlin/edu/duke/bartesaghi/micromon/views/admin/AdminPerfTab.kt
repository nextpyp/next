
package edu.duke.bartesaghi.micromon.views.admin

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.errorMessage
import edu.duke.bartesaghi.micromon.loading
import edu.duke.bartesaghi.micromon.services.PerfData
import edu.duke.bartesaghi.micromon.services.Services
import edu.duke.bartesaghi.micromon.toFixed
import io.kvision.core.Container
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.button
import io.kvision.html.div


class AdminPerfTab(val elem: Container) {

	val out = Tag(TAG.PRE)

	init {
		elem.div {
			button("Refresh")
				.onClick {
					poll()
				}
		}
		elem.add(out)
	}

	private fun poll() {

		// clear the old output
		out.content = null
		out.removeAll()

		val loading = elem.loading()

		AppScope.launch {

			val perf = try {
				Services.admin.perf()
			} catch (t: Throwable) {
				out.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loading)
			}

			out.content = perf.report()
		}
	}

	private fun PerfData.report(): String =
		"""
			|[Operating System]
			|${osinfo.report()}
			|
			|[Memory]
			|             JVM Mem:  ${jvmMem.report()}
			|       JVM Heap Eden:  ${jvmHeapEden.report()}
			|   JVM Heap Survivor:  ${jvmHeapSurvivor.report()}
			|        JVM Heap Old:  ${jvmHeapOld.report()}
			|
			|[Request Statistics]
			|${requests.report()}
		""".trimMargin()

	private fun PerfData.OSInfo.report(): String =
		"""
			|             Hostname:  $hostname
			|               Memory:  ${mem.report()}
			|                 CPUs:  $numCpus
			|    CPU Load (system):  ${(systemCpuLoad*100.0).toFixed(2)}%
			|  CPU Load (micromon):  ${(systemCpuLoad*100.0).toFixed(2)}%
			|         CPU Load Avg:  ${loadAvg.toFixed(2)}
			|     File Descriptors:  $openFileDescriptors of $maxFileDescriptors (${(openFileDescriptors*100.0/maxFileDescriptors).toFixed(2)}%)
		""".trimMargin()

	private fun PerfData.MemPool.report(): String =
		if (totalMiB != null) {
			"$totalMiB/$maxMiB MiB allocated, $usedMiB MiB (${usedPercent.toFixed(2)}%) used"
		} else {
			"$usedMiB/$maxMiB MiB (${usedPercent.toFixed(2)}%) used"
		}

	private fun PerfData.RequestStats.report(): String =
		"""
			| Requests in last 30s:  $numRequests
			|  Requests per second:  ${requestsPerSecond.toFixed(2)}
			|            Latencies:  ${requestLatency.report().split("\n").joinToString("\n                        ")}
		""".trimMargin()

	private fun PerfData.RequestStats.Latency?.report(): String =
		if (this == null) {
			"(no recent requests)"
		} else {
			"""
				|${ms0pct.leftPad(8)} ms at 0 pctl
				|${ms5pct.leftPad(8)} ms at 5 pctl
				|${ms10pct.leftPad(8)} ms at 10 pctl
				|${ms25pct.leftPad(8)} ms at 25 pctl
				|${ms50pct.leftPad(8)} ms at 50 pctl
				|${ms75pct.leftPad(8)} ms at 75 pctl
				|${ms90pct.leftPad(8)} ms at 90 pctl
				|${ms95pct.leftPad(8)} ms at 95 pctl
				|${ms100pct.leftPad(8)} ms at 100 pctl
			""".trimMargin()
		}

	private fun Long.leftPad(size: Int): String {
		var str = toString()
		while (str.length < size) {
			str = " $str"
		}
		return str
	}
}
