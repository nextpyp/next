package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.components.forms.enabled
import io.kvision.core.onEvent
import io.kvision.form.check.checkBox
import io.kvision.form.check.CheckBoxStyle
import io.kvision.form.text.TextInput
import io.kvision.html.*


/**
 * Shows the first, prev 100/10/1, next 1/10/100, last, etc buttons to navigate a big list.
 */
class BigListNav(
	val items: List<Any>,
	initialIndex: Int = items.size - 1,
	has100: Boolean = true,
	val onShow: (Int) -> Unit
) : Div(classes = setOf("big-list-nav")) {

	// start with the last item
	var currentIndex = initialIndex
		private set

	private val navFirst = button("", icon = "fas fa-step-backward").apply {
		onClick {
			showItem(0, true)
		}
		enabled = false
	}

	private val navBack100 = if (has100) button("100", icon = "fas fa-angle-double-left").apply {
		onClick {
			showItem(currentIndex - 100, true)
		}
		enabled = false
	} else null

	private val navBack10 = button("10", icon = "fas fa-angle-double-left").apply {
		onClick {
			showItem(currentIndex - 10, true)
		}
		enabled = false
	}

	private val navBack = button("1", icon = "fas fa-angle-left").apply {
		onClick {
			showItem(currentIndex - 1, true)
		}
		enabled = false
	}

	private val navIndex = TextInput(classes = setOf("index")).apply {
		onEvent {
			change = {
				onIndex()
			}
		}
	}
	private val navCount = Span(classes = setOf("count"))

	init {
		val self = this
		span(classes = setOf("counter")) {
			add(self.navIndex)
			span("of")
			add(self.navCount)
		}
	}

	private val navForward = button("1", icon = "fas fa-angle-right").apply {
		onClick {
			showItem(currentIndex + 1, true)
		}
		enabled = false
	}

	private val navForward10 = button("10", icon = "fas fa-angle-double-right").apply {
		onClick {
			showItem(currentIndex + 10, true)
		}
		enabled = false
	}

	private val navForward100 = if (has100) button("100", icon = "fas fa-angle-double-right").apply {
		onClick {
			showItem(currentIndex + 100, true)
		}
		enabled = false
	} else null

	private val navLast = button("", icon = "fas fa-step-forward").apply {
		onClick {
			showItem(items.size - 1, true)
		}
		enabled = false
	}

	private val navLive = checkBox(true, label = "Watch").apply {
		style = CheckBoxStyle.PRIMARY
		onEvent {
			change = {
				if (value) {
					showItem(items.size - 1, false)
				}
			}
		}
	}

	init {
		showItem(currentIndex, stopLive=false)
	}

	fun showItem(index: Int, stopLive: Boolean) {

		if (stopLive) {
			navLive.value = false
		}

		currentIndex = index
		updateUI()
		onShow(currentIndex)
	}

	/** call this to update the UI after adding a new item to the list */
	fun newItem() {
		if (navLive.value) {
			showItem(items.size - 1, false)
		} else {
			updateUI()
		}
	}

	/** call this to update the UI after adding clearing the items list */
	fun cleared() {
		currentIndex = -1
		navIndex.value = null
		navLive.value = true
		updateUI()
	}

	fun reshow() {
		showItem(currentIndex, false)
	}

	private fun updateUI() {

		navFirst.enabled = currentIndex > 0
		navBack100?.enabled = currentIndex >= 100
		navBack10.enabled = currentIndex >= 10
		navBack.enabled = currentIndex > 0

		writeIndex()
		navCount.content = "${items.size}"

		navForward.enabled = currentIndex + 1 < items.size
		navForward10.enabled = currentIndex + 10 < items.size
		navForward100?.enabled = currentIndex + 100 < items.size
		navLast.enabled = currentIndex != items.size - 1
	}

	private fun writeIndex() {
		navIndex.value = "${currentIndex + 1}"
	}

	private fun readIndex(): Int? =
		navIndex.value
			?.toIntOrNull()
			?.let { it - 1 }
			?.takeIf { it >= 0 && it < items.size }

	private fun onIndex() {

		// read the new index, if possible
		val newIndex = readIndex()

		if (newIndex == null) {
			// invalid, restore the old index
			writeIndex()
			return
		}

		// apply the new index
		showItem(newIndex, true)
	}
}
