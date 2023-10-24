package js.cookie

import kotlinext.js.Object


/**
 * See: https://github.com/js-cookie/js-cookie
 */
@JsModule("js-cookie")
@JsNonModule
external object Cookies {

	// just expose the read functions
	fun get(): Object
	operator fun get(key: String): String?
}
