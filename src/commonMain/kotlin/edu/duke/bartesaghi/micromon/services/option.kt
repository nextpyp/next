package edu.duke.bartesaghi.micromon.services


/**
 * KVision can't support nullable return types,
 * so use a list as workaround
 */
typealias Option<T> = List<T>

// NOTE: don't try to name this `get`, kotlinc apparently gets confused with the actual List methods named `get`
fun <T> Option<T>.unwrap(): T? =
	firstOrNull()

fun <T> T?.toOption(): Option<T> =
	if (this != null) {
		listOf(this)
	} else {
		emptyList()
	}
