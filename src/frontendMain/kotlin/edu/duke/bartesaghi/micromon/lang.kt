package edu.duke.bartesaghi.micromon

import js.FinalizationRegistry
import js.WeakRef
import js.stacktrace.StackTrace
import kotlinext.js.Object
import kotlinext.js.jsObject
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.await
import kotlinx.coroutines.launch


/**
 * Some js libraries return false instead of null,
 * but the Kotlin type wrappers can't really handle that.
 * So look for false values and change them to null.
 */
fun <T:Any> T?.falseToNull(): T? {
	if (asDynamic() === false) {
		return null
	}
	return this
}


operator fun Function<*>.invoke() {
	// don't know why this isn't provided by the stdlib ...
	this.asDynamic()()
}


fun Throwable.reportError(msg: String? = null) {

	// show prefix message, if any
	msg?.let { console.error(msg) }

	val type = try {
		this@reportError::class.simpleName
	} catch (e: dynamic) {
		"(unknown)"
	}

	console.error("""
		|NextPYP had an error:
		|     Type:  $type
		|  Message:  $message
	""".trimMargin())
	console.log("Collecting stack trace, please wait ...")

	val stack = this@reportError.asDynamic().stack

	// NOTE: can't use the regular AppScope.launch here, becuase it might have already been broken by an exception
	//       so start a new coroutine scope to handle the stack processing
	CoroutineScope(window.asCoroutineDispatcher()).launch {
		try {

			// use a library to parse the stack trace, since doing this correctly requires reading the source maps
			// NOTE: Kotlin multiplatform's errors aren't quite the same as native JS errors,
			//       so make an error-like plain JS object that should hopefully be good enough for the stack parsing
			StackTrace.fromError(jsObject {
				this.message = message
				this.stack = stack
			})
				.await()
				.joinToString("\n") { frame ->
					"${frame.functionName} @ ${frame.fileName}:${frame.lineNumber}:${frame.columnNumber}"
				}
				.let { console.error(it) }

		} catch (err: dynamic) {
			console.error(this)
			console.error("Also, error processing failed", err)
		} finally {

			// report causes too
			cause?.reportError("Caused By")
		}
	}
}


data class Quad<out A, out B, out C, out D>(
	val first: A,
	val second: B,
	val third: C,
	val fourth: D
) {

	override fun toString(): String = "($first, $second, $third, $fourth)"
}



class GarbageNotifier {

	private var nextId = 1
	private val registrations = HashMap<Int,Registration<*>>()
	private val registry = FinalizationRegistry { heldValue ->
		val id = heldValue as Int
		registrations.remove(id)
			?.callback?.invoke()
	}

	/**
	 * !!!WARNING!!!
	 * Do not capture the garbage target in this callback's closure!
	 * If you do, it will never be garbage collected!
	 */
	fun <T:Any> register(target: T, callback: (() -> Unit)? = null): Registration<T> {

		// make the id
		val id = nextId
		nextId += 1

		// regsiter it
		val unregisterToken = jsObject<Object>()
		registry.register(target, id, unregisterToken)

		val reg = object : Registration<T> {

			override val target = WeakRef(target)

			override var callback = callback

			override fun unregister() {
				registry.unregister(unregisterToken)
			}
		}
		registrations[id] = reg
		return reg
	}

	interface Registration<T> {

		val target: WeakRef<T>

		/**
		 * !!!WARNING!!!
		 * Do not capture the garbage target in this callback's closure!
		 * If you do, it will never be garbage collected!
		 */
		var callback: (() -> Unit)?

		fun unregister()
	}
}
