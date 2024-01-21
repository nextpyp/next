package js.hyperlist

import js.UnshadowedWidget
import js.hyperlist.raw.HyperList as RawHyperList
import kotlinext.js.jsObject
import org.w3c.dom.Element
import io.kvision.core.Container


class HyperList internal constructor(
	container: Container,
	classes: Set<String>,
	itemHeight: Int,
	total: Int,
	generator: (row: Int) -> Element
) : UnshadowedWidget(classes) {

	init {
		// add to the DOM immediately
		container.add(this)
	}

	val config = jsObject<RawHyperList.Config> {
		this.itemHeight = itemHeight
		this.total = total
		this.generate = { index -> generator(index.toInt()) }
	}

	private val hyperlist = RawHyperList(elem, config)

	fun refreshHyperList() =
		hyperlist.refresh(elem, config)
}

fun Container.hyperList(
	itemHeight: Int = 0,
	total: Int = 1, // NOTE: putting 0 here breaks hyperlist!
	classes: Set<String> = emptySet(),
	generator: (row: Int) -> Element
) = HyperList(
	this,
	classes,
	itemHeight,
	total,
	generator
)
