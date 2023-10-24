package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.views.ErrorView
import edu.duke.bartesaghi.micromon.views.Viewport
import kotlinext.js.jsObject
import org.w3c.dom.events.MouseEvent
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.html.Link
import io.kvision.navigo.Navigo
import io.kvision.routing.Strategy
import kotlin.js.RegExp


const val urlToken = "[^/]+"


// override the default Routing class,
// so we can have more control over the hash string
// and so we can have better pause behavior too
class Routing {

	companion object {
		const val hash = "#"
	}

	private val nav = Navigo("/", jsObject {
		this.hash = true
		this.strategy = Strategy.ONE.name
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

		// don't show a URL that's already showing
		// this apparently sends navigo into a NASTY loop where it tries to load thousands of routes at once
		// the loop is thankfully killed by the browser because it looks like (and is) routing API abuse
		// but we should avoid that death loop if we can, because it completely wrecks the UX
		val currentPath = nav.getCurrentLocation().hashString
		if (path == currentPath) {
			return
		}

		nav.navigate(path, jsObject {
			this.callHandler = false

			// setting these navigo options seems to prevent the death loop too
			this.callHooks = false
			this.force = true
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