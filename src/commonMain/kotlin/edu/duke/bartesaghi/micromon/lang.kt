package edu.duke.bartesaghi.micromon

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
