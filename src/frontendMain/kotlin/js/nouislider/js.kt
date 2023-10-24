@file:Suppress("UNCHECKED_CAST", "UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")

package js.nouislider

import org.w3c.dom.HTMLElement
import io.kvision.require


/**
 * See: https://refreshless.com/nouislider/
 */
val noUiSlider: NoUiSlider = require("nouislider")

external class NoUiSlider {

	fun create(elem: HTMLElement, options: Options): HTMLElement = definedExternally

	interface Range {
		var min: Number
		var max: Number
	}

	interface Pips {
		var mode: String // eg "steps"
		var stepped: Boolean
		var density: Number
	}

	interface Format {
		var to: (num: Number) -> String
		var from: (str: String) -> Number
	}

	interface Options {
		var range: Range
		var start: Array<Number>
		var margin: Number
		var limit: Number
		var connect: Boolean
		var direction: String // eg "rtl"
		var orientation: String // eg "vertical"
		var behaviour: String // eg "tag-drag"
		var tooltips: Array<Format>
		var format: Format
		var pips: Pips
		var step: Number
	}
}

val HTMLElement.noUiSlider get() = asDynamic().noUiSlider as NoUiSliderRuntime

external interface NoUiSliderRuntime {

	fun get(): Any /* String or Array<String> */

	fun set(value: Number)
	fun set(values: Array<Number>)

	fun updateOptions(options: NoUiSlider.Options)

	fun reset()

	fun on(eventName: String, block: () -> Unit)
}

fun NoUiSliderRuntime.getSingle() = get() as String
fun NoUiSliderRuntime.getMulti() = get() as Array<String>

fun NoUiSliderRuntime.setSingle(value: Number) = set(value)
fun NoUiSliderRuntime.setMulti(value: Array<Number>) = set(value)

fun NoUiSliderRuntime.onStart(block: () -> Unit) = on("start", block)
fun NoUiSliderRuntime.onSlide(block: () -> Unit) = on("slide", block)
fun NoUiSliderRuntime.onUpdate(block: () -> Unit) = on("update", block)
fun NoUiSliderRuntime.onChange(block: () -> Unit) = on("change", block)
fun NoUiSliderRuntime.onSet(block: () -> Unit) = on("set", block)
fun NoUiSliderRuntime.onEnd(block: () -> Unit) = on("end", block)
