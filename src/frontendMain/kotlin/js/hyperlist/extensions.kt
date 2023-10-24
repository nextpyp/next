package js.hyperlist

import js.UnshadowedWidget
import js.hyperlist.raw.HyperList as RawHyperList
import kotlinext.js.Object
import kotlinext.js.PropertyDescriptor
import kotlinext.js.jsObject
import org.w3c.dom.Element
import io.kvision.core.Container


class HyperList internal constructor(
	container: Container,
	itemHeight: Int,
	items: () -> Int,
	reverse: Boolean,
	classes: Set<String>,
	generator: (row: Int) -> Element
) : UnshadowedWidget(classes) {

	init {
		// add to the DOM immediately
		container.add(this)
	}

	val config = jsObject<RawHyperList.Config> {
		this.itemHeight = itemHeight
		this.total = 0
		this.reverse = reverse
		this.generate = { index -> generator(index.toInt()) }
	}.apply {
		Object.defineProperty(this, "total", jsObject<PropertyDescriptor<Number>> {
			get = items
		})
	}

	private val hyperlist = RawHyperList(elem, config)

	fun refreshHyperList() =
		hyperlist.refresh(elem, config)
}

fun Container.hyperList(
	itemHeight: Int,
	items: () -> Int,
	reverse: Boolean = false,
	classes: Set<String> = emptySet(),
	generator: (row: Int) -> Element
) = HyperList(
	this,
	itemHeight,
	items,
	reverse,
	classes,
	generator
)
