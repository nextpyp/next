package edu.duke.bartesaghi.micromon.mongo

import com.mongodb.client.FindIterable


/**
 * Mongo cursors need to be explicitly closed after use to free up resources.
 * Apparently the usual iteration machinery in Java/Kotlin doesn't do that automatically for us,
 * so we need to do it ourselves.
 */
inline fun <T,R> FindIterable<T>.useCursor(block: (Sequence<T>) -> R): R {
	cursor().use {
		return block(it.asSequence())
	}
}
