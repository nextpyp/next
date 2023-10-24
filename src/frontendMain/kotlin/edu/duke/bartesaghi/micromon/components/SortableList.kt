package edu.duke.bartesaghi.micromon.components

import io.kvision.core.Component
import io.kvision.core.onEvent
import io.kvision.html.Div
import io.kvision.html.div
import js.getHTMLElement
import org.w3c.dom.DragEvent


class SortableList<T:SortableList.Item>(
	val items: MutableList<T>,
	classes: Set<String>? = null
) : Div(classes = setOf("sortable-list") + (classes ?: emptySet())) {

	companion object {
		const val DRAGGING_CLASSNAME = "dragging"
		const val FORMAT = "app/SortableList"
	}

	interface Item : Component {
		val key: String
	}

	inner class DropTarget(val index: Int) : Div(classes = setOf("drop-target")) {

		init {

			div(classes = setOf("bar"))

			val self = this

			// wire up drag'n'drop events
			// NOTE: the KVision API wrappers for drag'n'drop are bad, use the raw DOM API instead
			onEvent {
				dragenter = {
					self.addCssClass(DRAGGING_CLASSNAME)
				}
				dragleave = {
					self.removeCssClass(DRAGGING_CLASSNAME)
				}
				dragover = { event ->
					// signal that we accept the drag by capturing the event
					if (event.itemKey != null) {
						event.stopPropagation()
						event.preventDefault()
					}
				}
				drop = { event ->
					self.removeCssClass(DRAGGING_CLASSNAME)
					event.stopPropagation()
					event.preventDefault()
					event.itemKey?.let { this@SortableList.reorder(it, index) }
				}
			}
		}
	}

	var onReorder: (() -> Unit) = {}

	private val itemElems = ArrayList<Div>()

	init {
		update()
	}

	fun update() {

		removeAll()
		itemElems.clear()

		for (i in 0 until items.size) {

			add(DropTarget(i))

			val item = items[i]
			val itemElem = div(classes = setOf("item")) {
				add(item)

				// NOTE: the KVision API wrappers for drag'n'drop are bad, use the raw DOM API instead
				draggable = true
				onEvent {
					dragstart = { event ->
						event.itemKey = item.key
					}
				}
			}
			itemElems.add(itemElem)
		}

		add(DropTarget(items.size))
	}

	private fun reorder(key: String, dstIndex: Int) {

		// find the dragged item index
		val srcIndex = items
			.indexOfFirst { it.key == key }
			.takeIf { it >= 0 }
			?: return

		var numAnimationsRunning = 0
		fun Div.animate(classname: String) {

			val elem = getHTMLElement()
				?: return
			elem.addEventListener("animationend", {
				numAnimationsRunning -= 1
				if (numAnimationsRunning == 0) {

					// actually update the control after all the animations finish
					this@SortableList.update()
				}
			})

			removeCssClass(classname)
			addCssClass(classname)
			numAnimationsRunning += 1
		}

		// move items towards the source to make room for this item at the destination
		val item = items[srcIndex]
		itemElems[srcIndex].animate("fade-out")
		if (dstIndex > srcIndex + 1) {
			for (i in srcIndex + 1 until dstIndex) {
				itemElems[i].animate("slide-up")
			}
			for (i in srcIndex until dstIndex - 1) {
				items[i] = items[i + 1]
			}
			items[dstIndex - 1] = item
		} else if (dstIndex < srcIndex) {
			for (i in srcIndex - 1 downTo dstIndex) {
				itemElems[i].animate("slide-down")
			}
			for (i in srcIndex downTo dstIndex + 1) {
				items[i] = items[i - 1]
			}
			items[dstIndex] = item
		} else {
			return
		}

		onReorder()
	}
}


private var DragEvent.itemKey: String?
	get() =
		dataTransfer?.getData(SortableList.FORMAT)
	set(value) {
		if (value != null) {
			dataTransfer?.setData(SortableList.FORMAT, value)
		} else {
			dataTransfer?.clearData(SortableList.FORMAT)
		}
	}
