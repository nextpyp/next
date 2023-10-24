package js.photoswipe

import org.w3c.dom.HTMLElement
import io.kvision.require


/**
 * See: https://photoswipe.com/documentation/getting-started.html
 */
@JsModule("photoswipe")
@JsNonModule
external class PhotoSwipe(
	elem: HTMLElement,
	ui: dynamic,
	items: Array<Item>,
	options: Options = definedExternally
) {
	fun init()
}

val photoswipeUiDefault = require("photoswipe/dist/photoswipe-ui-default.js")

interface Item {
	var src: String
	var w: Int
	var h: Int
	var msrc: String
	var title: String
}

interface Options {
	var index: Int
	var history: Boolean
}

// photoswipe.css
// default-skin/default-skin.css (also images)
