package js.stacktrace

import kotlin.js.Promise


/**
 * https://www.npmjs.com/package/stacktrace-js
 */
@JsModule("stacktrace-js")
@JsNonModule
external object StackTrace {

	fun fromError(err: dynamic): Promise<Array<StackFrame>>

	interface StackFrame {
		var functionName: String
		var fileName: String
		var lineNumber: Number
		var columnNumber: Number
	}
}
