package edu.duke.bartesaghi.micromon

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.ceil


fun Int.divideUp(i: Int) = (this + i - 1)/i
fun Long.divideUp(l: Long) = (this + l - 1)/l


/**
 * Turns this into a nice, round number ... whatever that means
 */
fun Double.niceAndRound(): Int =
	zeroOutLowOrderDigits()

fun Double.zeroOutLowOrderDigits(): Int {
	val str = ceil(this).toString()
	val round = str[0].toString() + "0".repeat(str.length - 1)
	return round.toInt()
}


interface Identified {
	val id: String
}


fun <KI,VI,KO,VO> Map<KI,VI>.mapKV(transform: (Map.Entry<KI,VI>) -> Pair<KO,VO>): Map<KO,VO> {
	val out = LinkedHashMap<KO,VO>()
	for (entryIn in this) {
		val (keyOut, valueOut) = transform(entryIn)
		out[keyOut] = valueOut
	}
	return out
}


fun String.quote() = "\"$this\""
fun String.unquote() = this.trim { it == '"' }


fun String.substringAfterFirst(c: Char): String? =
	indexOfFirst { it == c }
		.takeIf { it >= 0 }
		?.let { pos -> substring(pos + 1) }


fun <T> MutableList<T>.setAll(other: List<T>) {
	clear()
	addAll(other)
}


/**
 * Copies the set, removes the element from it, and returns the new set.
 * Converts the set to a HashSet, regardless of the original set implementation.
 */
fun <T> Set<T>.without(value: T): Set<T> =
	HashSet(this).apply {
		remove(value)
	}


/** async version of AutoCloseable */
interface SuspendCloseable {

	suspend fun closeAll()

	suspend fun close() = withContext(NonCancellable) {
		// NOTE: a canceled coroutine won't resume past an await point, but we might have suspend
		//       cleanup functions in here, so use NonCancelable to make sure cleanup finishes
		closeAll()
	}
}


suspend fun <T:SuspendCloseable,R> T.use(block: suspend (T) -> R): R =
	try {
		block(this)
	} finally {
		close()
	}



interface PeekingIterator<T> : Iterator<T> {
	fun peek(): T
}

fun <T> Iterator<T>.peeking(): PeekingIterator<T> {

	val src = this
	if (src is PeekingIterator) {
		return src
	}

	return object : PeekingIterator<T> {

		private val next = ArrayList<T>()

		override fun hasNext(): Boolean =
			next.isNotEmpty() || src.hasNext()

		override fun next(): T {

			if (next.isEmpty()) {
				return src.next()
			}

			val out = next[0]
			next.clear()
			return out
		}

		override fun peek(): T {
			if (next.isEmpty()) {
				next.add(src.next())
			}
			return next[0]
		}
	}
}
