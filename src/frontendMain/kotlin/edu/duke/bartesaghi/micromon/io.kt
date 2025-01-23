package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.services.ImageSize
import edu.duke.bartesaghi.micromon.services.RealTimeC2S
import edu.duke.bartesaghi.micromon.services.RealTimeS2C
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.delay
import org.w3c.dom.Image
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Date


/**
 * Delay at least the desired time,
 * but don't add extra waiting time to the wrapped operation.
 */
suspend inline fun <R> delayAtLeast(ms: Long, block: () -> R): R {

	// run the block, and time it
	val startMs = Date.now().toLong()
	val result = block()
	val elapsedMs = Date.now().toLong() - startMs

	// delay more, if we need to
	val remainingMs = ms - elapsedMs
	if (remainingMs > 0) {
		delay(remainingMs)
	}

	return result
}


class Profiler {

	private val startMs = Date.now().toLong()

	fun elapsedMs(): Int = (Date.now().toLong() - startMs).toInt()

	fun log(msg: String) {
		console.log(msg, elapsedMs(), "ms")
	}
}

inline fun <T> timeIt(name: String, block: () -> T): T {
	val profiler = Profiler()
	val result = block()
	profiler.log(name)
	return result
}


fun <T:Comparable<T>> List<T>.median() =
	takeIf { it.isNotEmpty() }
	?.sorted()
	?.let { it[it.size/2] }

fun Number?.toFixed(size: Int): String =
	if (this != null) {
		this.asDynamic().toFixed(size) as String
	} else {
		"N/A"
	}


fun Long.toBytesString(): String =
	when (this) {
		in 0 .. 1_000 -> "$this B"
		in 0 .. 1_000_000 -> "${(this/1024.0).toFixed(1)} KiB"
		in 0 .. 1_000_000_000 -> "${(this/1024.0/1024.0).toFixed(1)} MiB"
		in 0 .. 1_000_000_000_000 -> "${(this/1024.0/1024.0/1024.0).toFixed(1)} GiB"
		else -> "${(this/1024.0/1024.0/1024.0/1024.0).toFixed(1)} TiB"
	}


fun Int.formatSecondsDuration(): String =
	when (this) {
		in 0 .. 60 -> "$this seconds"
		in 61 .. 3600 -> "${(this/60.0).toFixed(1)} minutes"
		else -> "${(this/3600.0).toFixed(1)} hours"
	}

// SEE: https://stackoverflow.com/questions/53206936/add-commas-or-point-every-3-digits-using-kotlin
fun Int.formatWithDigitGroupsSeparator(): String = toString()
	.reversed()
	.chunked(3)
	.joinToString(",")
	.reversed()

fun Long.formatWithDigitGroupsSeparator(): String = toString()
	.reversed()
	.chunked(3)
	.joinToString(",")
	.reversed()

suspend inline fun <reified M : RealTimeC2S> SendChannel<String>.sendMessage(msg: M) {
	send(msg.toJson())
}

fun <I,O> ChannelIterator<I>.map(mapper: (I) -> O): ChannelIterator<O> =
	object : ChannelIterator<O> {
		override suspend fun hasNext() = this@map.hasNext()
		override fun next() = mapper(this@map.next())
	}

interface ReceiveChannelIterator<T> {
	operator fun iterator(): ChannelIterator<T>
}

fun ReceiveChannel<String>.messages(): ReceiveChannelIterator<RealTimeS2C> =
	object : ReceiveChannelIterator<RealTimeS2C> {
		override operator fun iterator() =
			this@messages.iterator().map { RealTimeS2C.fromJson(it) }
	}

/**
 * Waits for the next message and returns it.
 * Throws an excepion if the message has an unexpected type.
 * Returns null if the connection was closed.
 */
suspend inline fun <reified M : RealTimeS2C> ReceiveChannel<String>.receiveMessage(): M =
	when (val msg = RealTimeS2C.fromJson(receive())) {
		is M -> msg
		is RealTimeS2C.Error -> throw IllegalArgumentException("Received error from server:\n${msg.name}\n${msg.msg}")
		else -> throw UnexpectedMessageException(msg, M::class.simpleName)
	}

class UnexpectedMessageException(val msg: Any, val expected: String?) :
	IllegalArgumentException("Expected message type ${expected ?: "(unknown)"}, but got: ${msg::class.simpleName ?: "(unknown)"}")


data class ImageSizes(
	val width: Int,
	val height: Int
) {

	companion object {

		fun from(size: ImageSize): ImageSizes =
			ImageSizes(size.approxWidth, size.approxWidth)
	}
}


suspend fun fetchImageSizes(url: String): ImageSizes =
	suspendCoroutine { continuation ->
		val image = Image()
		image.addEventListener("load", {
			continuation.resume(ImageSizes(
				width = image.naturalWidth,
				height = image.naturalHeight
			))
		})
		image.src = url
	}
