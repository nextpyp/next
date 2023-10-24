package js.hyperlist.raw

import org.w3c.dom.Element


/**
 * See: https://github.com/tbranyen/hyperlist
 */
@JsModule("hyperlist")
@JsNonModule
external class HyperList(
	element: Element,
	config: Config
) {

	fun refresh(element: Element, config: Config)

	interface Config {
		var itemHeight: Number
		var total: Number
		var generate: (index: Number) -> Element
		var reverse: Boolean
	}
}
