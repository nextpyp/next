package edu.duke.bartesaghi.micromon.services


/**
 * Essentially, a suspend-able implementation of lazy {}
 */
class ServerVal<T:Any>(val getter: suspend () -> T) {

	private var value: Any = NOT_A_VALUE

	val hasValue get() = value !== NOT_A_VALUE

	suspend fun get(): T {
		if (!hasValue) {
			value = getter()
		}
		@Suppress("UNCHECKED_CAST")
		return value as T
	}

	// tragically, this can't be a property delegate class
	// because Kotlin doesn't support suspend operators
}

private object NOT_A_VALUE
