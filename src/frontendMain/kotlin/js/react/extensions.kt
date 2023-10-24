package js.react

import kotlinext.js.js
import org.w3c.dom.events.MouseEvent


/**
 * A quick an dirty DSL for making react elements easily
 */
fun React.elems(block: ReactBuilder.() -> Unit): Array<dynamic> {
	val builder = ReactBuilder()
	builder.block()
	return builder.elems()
}

class ReactBuilder {

	private val elems = ArrayList<dynamic>()

	fun elems() = elems.toTypedArray()

	fun text(text: String) {
		elems.add(text)
	}

	private fun (ReactBuilder.() -> Unit)?.eval(): Array<dynamic> {
		val builder = ReactBuilder()
		if (this != null) {
			builder.this()
		}
		return builder.elems()
	}

	fun <T:React.Component> component(jsClass: JsClass<T>, props: dynamic, block: (ReactBuilder.() -> Unit)? = null) =
		React.createElement(
			jsClass,
			props,
			*block.eval()
		).also {
			elems.add(it)
		}

	private fun tag(name: String, block: (ReactBuilder.() -> Unit)? = null, props: ((dynamic) -> Unit)? = null) =
		React.createElement(
			name,
			js {
				props?.invoke(this)
			},
			*block.eval()
		).also {
			elems.add(it)
		}

	fun div(className: String? = null, block: (ReactBuilder.() -> Unit)? = null) =
		tag("div", block) {
			it.className = className
		}

	fun span(className: String? = null, block: (ReactBuilder.() -> Unit)? = null) =
		tag("span", block) {
			it.className = className
		}

	fun i(className: String? = null, title: String? = null, block: (ReactBuilder.() -> Unit)? = null) =
		tag("i", block) {
			it.className = className
			it.title = title
		}

	fun icon(name: String, className: String? = null) =
		tag("i") {
			it.className = "$className $name"
		}

	fun button(className: String? = null, onClick: (() -> Unit)? = null, enabled: Boolean = true, title: String? = null, block: (ReactBuilder.() -> Unit)? = null) =
		tag("button", block) {
			it.className = className
			it.onClick = onClick
			it.disabled = !enabled
			it.title = title
		}

	fun img(src: String, className: String? = null, title: String? = null) =
		tag("img") {
			it.src = src
			it.className = className
			it.title = title
		}

	fun dropdownButton(className: String? = null, onMouseDown: ((MouseEvent) -> Unit)? = null, enabled: Boolean = true, title: String? = null, buttonBlock: (ReactBuilder.() -> Unit)? = null, menuBlock: (ReactBuilder.() -> Unit)? = null) =
		div("btn-group") {
			tag("button", buttonBlock) {
				it.className = className
				it.onMouseDown = onMouseDown
				it.disabled = !enabled
				it.title = title
				it["data-toggle"] = "dropdown"
			}
			tag("div", menuBlock) {
				it.className = "dropdown-menu"
				it.onMouseDown = onMouseDown
			}
		}

	fun dropdownHeader(block: (ReactBuilder.() -> Unit)? = null) =
		tag("h6", block) {
			it.className = "dropdown-header"
		}

	fun dropdownDivider() =
		tag("div") {
			it.className = "dropdown-divider"
		}

	fun dropdownItem(enabled: Boolean = true, title: String? = null, onClick: (() -> Unit)? = null, block: (ReactBuilder.() -> Unit)? = null) =
		tag("a", block) {
			it.className = "dropdown-item"
			if (!enabled) {
				it.className += " disabled"
			}
			it.title = title
			if (enabled) {
				it.onClick = onClick
			}
		}
}
