package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.services.PerfData
import io.ktor.application.*
import io.ktor.request.*
import io.ktor.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit


object RequestMetrics : ApplicationFeature<Application,Void,MetricsFeature> {

	override val key: AttributeKey<MetricsFeature> = AttributeKey("Metrics")

	override fun install(pipeline: Application, configure: Void.() -> Unit): MetricsFeature {

		val metrics = MetricsFeature()

		// NOTE: for all events, context and call are the same instance!

		// listen to events near the start and end of calls
		// these events aren't perfectly what we're looking for, but they're pretty close
		pipeline.intercept(ApplicationCallPipeline.Setup) {
			metrics.start(call)
		}
		pipeline.intercept(ApplicationCallPipeline.Call) {
			metrics.end(call)
		}

		return metrics
	}
}


class MetricsFeature {

	companion object {

		val windowSize = 30.seconds
	}

	private data class CallInfo(
		val id: Int,
		val uri: String,
		val startNs: Long
	)

	private data class CallStats(
		val startNs: Long,
		val endNs: Long
	) {

		val elapsedMs: Long get() =
			(endNs - startNs).nanoseconds.inWholeMilliseconds
	}

	private val calls = ConcurrentHashMap<Int,CallInfo>()
	private val callStats = ConcurrentLinkedDeque<CallStats>()


	private val ApplicationCall.id: Int get() =
		System.identityHashCode(this)
	// NOTE: ApplicationCall.callId is always null
	//   so use the JVM id for the call object

	fun start(call: ApplicationCall) {
		val now = System.nanoTime()
		val id = call.id
		calls[id] = CallInfo(
			id,
			uri = call.request.uri,
			startNs = now
		)
	}

	fun end(call: ApplicationCall) {

		val now = System.nanoTime()
		val id = call.id

		val info = calls.remove(id)
		if (info != null) {
			collect(info, now)
		}
	}

	private fun collect(info: CallInfo, endNs: Long) {

		val stats = CallStats(info.startNs, endNs)

		// report slow requests
		if (stats.elapsedMs > 5.seconds.inWholeMilliseconds) {
			if (info.uri.startsWith("/ws/")) {
				// but ignore websocket requests
				// those just appear slow just because they're long-lived streaming connections
			} else {
				Backend.log.warn("Slow request: ${stats.elapsedMs} ms, uri=${info.uri}")
			}
		}

		callStats.addLast(stats)
		pruneCalls()
		// TODO: might want to switch to a bounded-length ConcurrentArrayDeque?
	}

	private fun pruneCalls() {

		// remove the calls older than the window
		val cutoffNs = System.nanoTime() - windowSize.inWholeNanoseconds
		while (true) {
			val oldest = callStats.firstOrNull()
				?: break
			if (oldest.startNs < cutoffNs) {
				try {
					callStats.removeFirst()
				} catch (ex: NoSuchElementException) {
					// we ran out of things to remove concurrently, no big deal, just stop
					break
				}
			} else {
				break
			}
		}
	}

	fun report(): PerfData.RequestStats {

		pruneCalls()

		// copy all the current call stats,
		// but don't pick up any calls that were concurrently added since we started here
		val now = System.nanoTime()
		val stats = ArrayList<CallStats>()
		for (s in callStats) {
			if (s.endNs < now) {
				stats.add(s)
			} else {
				break
			}
		}

		// sort by elapsed time, so we can do timing statistics
		stats.sortBy { it.elapsedMs }

		return PerfData.RequestStats(
			numRequests = stats.size,
			requestsPerSecond = stats.size/windowSize.toDouble(DurationUnit.SECONDS),
			requestLatency = stats
				.takeIf { stats.isNotEmpty() }
				?.let {

					operator fun List<CallStats>.get(pct: Double): CallStats =
						this[((stats.size - 1)*pct/100.0).roundToInt()]

					PerfData.RequestStats.Latency(
						ms0pct = it[0.0].elapsedMs,
						ms5pct = it[5.0].elapsedMs,
						ms10pct = it[10.0].elapsedMs,
						ms25pct = it[25.0].elapsedMs,
						ms50pct = it[50.0].elapsedMs,
						ms75pct = it[75.0].elapsedMs,
						ms90pct = it[90.0].elapsedMs,
						ms95pct = it[95.0].elapsedMs,
						ms100pct = it[100.0].elapsedMs
					)
				}
		)
	}
}
