package edu.duke.bartesaghi.micromon

import io.kvision.html.CustomTag
import kotlin.reflect.KProperty


private class SvgProp {

	operator fun getValue(self: CustomTag, property: KProperty<*>): String? =
		self.getAttribute(property.name)

	operator fun setValue(self: CustomTag, property: KProperty<*>, value: String?) {
		if (value != null) {
			self.setAttribute(property.name, value)
		} else {
			self.removeAttribute(property.name)
		}
	}
}


open class Svg(classes: Set<String> = emptySet()) : CustomTag("svg", classes=classes) {

	var preserveAspectRatio: String? by SvgProp()
	var viewBox: String? by SvgProp()

	open class G(classes: Set<String> = emptySet()) : CustomTag("g", classes=classes)

	open class Line(classes: Set<String> = emptySet()) : CustomTag("line", classes=classes) {
		var x1: String? by SvgProp()
		var y1: String? by SvgProp()
		var x2: String? by SvgProp()
		var y2: String? by SvgProp()
		val vectorEffect: String? by SvgProp()
	}
}
