package js.ansicolor

// https://www.npmjs.com/package/ansicolor
// https://github.com/xpl/ansicolor/blob/master/ansicolor.js

@JsModule("ansicolor")
@JsNonModule
external object Ansicolor {

	fun isEscaped(text: String): Boolean
	fun parse(text: String): Colors
}

external class Colors {
	val spans: Array<SegmentInfo>
}

external class SegmentInfo {
	var css: String
	var italic: Boolean
	var bold: Boolean
	var color: ColorInfo
	var bgColor: ColorInfo
	var text: String
}

external class ColorInfo {
	var name: String
	var bright: Boolean
	var dim: Boolean
}
