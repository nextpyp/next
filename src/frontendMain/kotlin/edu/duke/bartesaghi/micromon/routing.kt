package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.views.ErrorView
import edu.duke.bartesaghi.micromon.views.Viewport
import kotlinext.js.jsObject
import org.w3c.dom.events.MouseEvent
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.html.Link
import io.kvision.navigo.Navigo
import kotlin.js.RegExp


const val urlToken = "[^/]+"


/**
 * Override the default Routing class,
 * so we can have more control over the hash string
 * and so we can have better URL showing behavior too.
 *
 * NOTE:
 * This code is based on KVision's bundling of Navigo, which has a ... complicated provenance.
 *
 * We're using KVision v4.5.0 which bundles io.kvision:navigo-kotlin-ng:0.0.1
 *   https://github.com/rjaros/kvision/blob/4.5.0/gradle.properties
 * That module relies on Navigo v8.8.12
 *   https://github.com/rjaros/navigo-kotlin-ng/tree/0.0.1
 * There's also a v0.0.2 module based on Navigo v8.11.1
 *   https://github.com/rjaros/navigo-kotlin-ng/tree/0.0.2
 *   KVision adopted it in v5.0.0
 *     https://github.com/rjaros/kvision/commit/021e32600a76d77893a621d7f07ca5055221733a
 *     https://github.com/rjaros/kvision/blob/58801e97b8098c24c7764c261859567e099fb4a0/gradle.properties
 * There's also apparently a v0.0.3 module
 *   Reason?
 *     https://github.com/rjaros/kvision/issues/405
 *   This is the newest one
 *   KVision adopted it in v5.10.1
 *     https://github.com/rjaros/kvision/commit/0cf495014f7e9278d5029f0dc70fe9e2739c592b
 *     https://github.com/rjaros/kvision/commit/377f3ad01f2e468a7d6b75f779b9bd1ff7320568
 *
 * Alas, Navigo stopped using version tags after v8.1, but here are roughly the code snapshots:
 *   v8.8.12
 *     https://github.com/krasimir/navigo/tree/0b6df8c6e877fdd46500dd159f6e45c221048982
 *   v8.11.1
 *     https://github.com/krasimir/navigo/tree/8784291784b898f486f565e7d3d5cf44297d250e
 *     this appears to be the newest release!
 */
class Routing {

	companion object {
		const val hash = "#"
	}

	private val nav = Navigo("/", jsObject {
		this.hash = true
		this.strategy = "ONE"
		this.noMatchWarning = false
	})

	fun register(block: () -> Unit) {
		nav.on(block)
	}

	/**
	 * Convert the RegExp string into a format Navigo expects,
	 * including dealing with the / characters that signal RegExp syntax at the language level
	 * in JavaScript, but aren't present in the Kotlin implementation of RegExp
	 */
	private fun String.toRegex(): RegExp {

		var it = this

		// strip off the flanking ^ and $ for now,
		// but remember, so we can put them back later
		val hasCaret = it.startsWith('^')
		if (hasCaret) {
			it = it.substring(1)
		}
		val hasDollar = it.endsWith('$')
		if (hasDollar) {
			it = it.substring(0, it.length - 1)
		}

		// js-native RegExp notation has flanking / characters as part of the language
		// navigo is trying to be cute and including that as part of the matching
		// so to match what navigo is looking for, we actually have to remove them from the RegExp,
		// since the Kotlin syntax for RegExp doesn't have extra language-level processing
		if (it.startsWith('/')) {
			it = it.substring(1)
		}
		if (it.endsWith('/')) {
			it = it.substring(0, it.length - 1)
		}

		// but the ^ and $ back on, if needed
		if (hasCaret) {
			it = "^$it"
		}
		if (hasDollar) {
			it = "$it$"
		}

		return RegExp(it)
	}

	fun register(path: String, block: () -> Unit) {
		nav.on(path.toRegex(), {
			block()
		})
	}

	fun registerParams(regex: String, block: (String) -> Unit) {
		nav.on(regex.toRegex(), {
			block(
				it.data[0] as String
			)
		})
	}
	fun registerParams(regex: String, block: (String, String) -> Unit) {
		nav.on(regex.toRegex(), {
			block(
				it.data[0] as String,
				it.data[1] as String
			)
		})
	}
	fun registerParams(regex: String, block: (String, String, String) -> Unit) {
		nav.on(regex.toRegex(), {
			block(
				it.data[0] as String,
				it.data[1] as String,
				it.data[2] as String
			)
		})
	}
	fun registerParams(regex: String, block: (String, String, String, String) -> Unit) {
		nav.on(regex.toRegex(), {
			block(
				it.data[0] as String,
				it.data[1] as String,
				it.data[2] as String,
				it.data[3] as String
			)
		})
	}
	fun registerParams(regex: String, block: (String, String, String, String, String) -> Unit) {
		nav.on(regex.toRegex(), {
			block(
				it.data[0] as String,
				it.data[1] as String,
				it.data[2] as String,
				it.data[3] as String,
				it.data[4] as String
			)
		})
	}

	fun registerParamsList(regex: String, block: (Array<String>) -> Unit) {
		nav.on(regex.toRegex(), {
			block(it.data as Array<String>)
		})
	}

	fun register(routeds: List<Routed>, viewport: Viewport) {

		// set up all the routes
		for (routed in routeds) {
			routed.register(this, viewport)
		}

		// add a catchall route
		nav.notFound({
			viewport.setView(ErrorView("Page not found"))
		}, jsObject {
			this.before = { done, match ->
				console.log("Navigo found no matching route: ", match)
				done()
			}
		})

		// load content for the initial url
		nav.resolve()
	}

	/**
	 * navigate to the url, calling the url handlers
	 */
	fun go(path: String) {
		nav.navigate(path)
	}

	/**
	 * don't actually call the url handlers,
	 * but update the address bar to show the url anyway
	 */
	fun show(path: String) {
		nav.navigate(path, jsObject {
			// tell navigo not to actually run any route handlers,
			// since we just want to show the URL but not actually navigate anywhere
			this.callHandler = false
		})
	}
}
val routing = Routing()

interface Routed {
	fun register(routing: Routing, viewport: Viewport)
}

fun Widget.onClick(block: (MouseEvent) -> Unit) {
	onEvent {
		click = { event ->

			// don't let the browser use the click to e.g. follow a link
			event.preventDefault()

			block(event)
		}
	}
}

fun Widget.onGo(path: String) = apply {
	if (this is Link) {
		url = "${Routing.hash}$path"
	}
	onClick {
		routing.go(path)
	}
}

fun Widget.onShow(path: String, block: () -> Unit) {
	if (this is Link) {
		url = "${Routing.hash}$path"
	}
	onClick {
		routing.show(path)
		block()
	}
}