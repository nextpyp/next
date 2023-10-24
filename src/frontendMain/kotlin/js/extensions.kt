package js

import com.github.snabbdom.VNode
import org.w3c.dom.HTMLElement
import io.kvision.core.Component
import io.kvision.core.Widget
import kotlinext.js.getOwnPropertyNames
import kotlinx.browser.document
import kotlinx.html.InputType
import kotlinx.html.div
import kotlinx.html.dom.create
import kotlinx.html.input
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.MouseEvent
import kotlin.js.Console
import kotlin.js.Date


// the Kotlin stdlib for js doesn't define trace() for some reason...
fun Console.trace(vararg o: Any?) {
	asDynamic().trace(o)
}


// javascript function... somehow not already defined in the kotlin/js libs
external fun decodeURIComponent(encodedURI: String): String


// NOTE: the cwd for require() is apparently:
// $PROJECT_PATH/build/kotlin-js-min/frontend/main'

/* WARNING: WARNING: DANGER WILL ROBINSON!!
	just defining this function, even if nothing calls it,
	is enough to send node/webpack into a death spiral!!

	I have no idea why, but webpack spams its memory manager and runs
	out of memory for some reason? Maybe an issue with recursion?

	So, I guess don't make any kind of wrappers for the require() function?
	It must be a magic function somehow.

inline fun requireMod(moduleName: String, path: String) =
	require("../../../node_modules/$moduleName/$path")
*/

/* WARNING:
	The require() function is indeed magic!
	The arguemnt must be a compile-time literal, so approaches
	like this won't work either:

fun modPath(moduleName: String, path: String) =
	"../../../node_modules/$moduleName/$path"
*/


fun Component.getHTMLElement(): HTMLElement? =
	getElement()
		?.let { node ->
			node as? HTMLElement
				?: throw RuntimeException("DOM node not an HTML element, instead a: $node")
		}

fun Component.getHTMLElementOrThrow(): HTMLElement =
	getHTMLElement()
		?: throw RuntimeException("doesn't have a DOM node, try adding component to DOM first")


data class ScreenPos(val x: Double, val y: Double)

fun MouseEvent.clickRelativeTo(elem: HTMLElement, normalize: Boolean): ScreenPos {
	val rect = elem.getBoundingClientRect()
	var clickX = clientX - rect.x
	var clickY = clientY - rect.y
	if (normalize) {
		clickX /= rect.width
		clickY /= rect.height
	}
	return ScreenPos(clickX, clickY)
}

fun MouseEvent.clickRelativeTo(widget: Widget, normalize: Boolean): ScreenPos? =
	widget.getHTMLElement()
		?.let { clickRelativeTo(it, normalize) }


/**
 * KVision uses this silly shadow DOM thing (via snabbdom),
 * which means anything other JS library that modifies the
 * DOM (like Plotly) will confuse the hell out of KVision.
 * Alas, we need to workaround this issue so we can use other JS
 * libs with KVision.
 *
 * Hence, this class.
 *
 * We solve the problem (hopefully) by hooking into snabbdom
 * to find out when it would delete the DOM nodes for the plot.
 * Instead of deleting them, we save them so they can be
 * restored to the DOM later.
 */
open class UnshadowedWidget(classes: Set<String>? = null) : Widget() {

	// create a div element that snabdom can't mess around with
	val elem: HTMLElement =
		document.create.div(classes = classes?.joinToString(" "))

	init {
		// Widget make the classes constuctor arg internal for some reason
		// so we'll have to add the css classes after construction
		classes?.forEach { addCssClass(it) }

		addAfterInsertHook h@{ vnode ->

			// replace this vnode's DOM node with the saved node
			vnode.elm?.let { elm ->
				elm.parentNode?.replaceChild(elem, elm)
			}

			// update the vnode reference itself in snabdom
			vnode.elm = elem
		}
	}

	final override fun addAfterInsertHook(hook: (VNode) -> Unit) =
		super.addAfterInsertHook(hook)
}

fun unshadow(elem: HTMLElement) =
	UnshadowedWidget().apply {
		this.elem.appendChild(elem)
	}

open class UnshadowedCheck(
	inputType: InputType,
	name: String,
	classes: Set<String>? = null
) : Widget() {

	// create an input element that snabdom can't mess around with
	val elem: HTMLInputElement =
		document.create.input(
			type = inputType,
			classes = classes?.joinToString(" "),
			name = name
		) as HTMLInputElement

	init {
		// Widget make the classes constuctor arg internal for some reason
		// so we'll have to add the css classes after construction
		classes?.forEach { addCssClass(it) }

		addAfterInsertHook h@{ vnode ->

			// replace this vnode's DOM node with the saved node
			vnode.elm?.let { elm ->
				elm.parentNode?.replaceChild(elem, elm)
			}

			// update the vnode reference itself in snabdom
			vnode.elm = elem
		}
	}

	final override fun addAfterInsertHook(hook: (VNode) -> Unit) =
		super.addAfterInsertHook(hook)
}


/** an interface to JS objects that behave more like maps */
interface MapObject<T>

operator fun <T> MapObject<T>.get(key: String): T =
	asDynamic()[key] as T

operator fun <T> MapObject<T>.set(key: String, value: T) {
	asDynamic()[key] = value
}

val <T> MapObject<T>.keys: Array<String> get() = getOwnPropertyNames()

val <T> MapObject<T>.values: List<T> get() = keys.map { get(it) }


// I have no idea how Kotlin/js normally handles raw js iterators,
// but here's one attempt quick shim
class JsIterator<T>(private val src: dynamic) : Iterator<T> {

	private val iter = run {
		// need a local variable for the js() compiler magic to work
		@Suppress("UNUSED_VARIABLE") // no, really, it's used in js-land
		val src = src
		js("src[Symbol.iterator]()")
	}

	private var item: T? = null
	private var done = false

	init {
		advance()
	}

	private fun advance() {
		val result = iter.next()
		done = result.done as Boolean
		item = if (!done) {
			result.value as T
		} else {
			null
		}
	}

	override fun hasNext(): Boolean =
		!done

	override fun next(): T {
		val item = this.item
			?: throw NoSuchElementException()
		advance()
		return item
	}
}


// https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Intl/DateTimeFormat
external object Intl {

	class DateTimeFormat(locale: String, options: DateTimeFormatOptions) {
		fun formatToParts(date: Date): Array<DateParts>
	}

	interface DateTimeFormatOptions {
		var year: String
		var month: String
		var day: String
		var hour: String
		var hourCycle: String
		var minute: String
		var second: String
	}
}

external class DateParts {
	var type: String
	var value: String
}
