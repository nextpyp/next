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
class BigListNav private constructor(
	private var core: Core,
	var onShow: (Int) -> Unit
) : Div(classes = setOf("big-list-nav")) {

	constructor(
		items: List<Any>,
		initialIndex: Int? = items.indices.lastOrNull(),
		// NOTE: don't use if-like constructs in the above expression or it will trigger a compiler bug! =(
		initialLive: Boolean = true,
		has100: Boolean = true,
		onShow: (Int) -> Unit = {}
	) : this(Core(items, initialIndex, initialLive, has100), onShow)

	class Core(
		val items: List<Any>,
		var index: Int?,
		var live: Boolean,
		val has100: Boolean
	) {
		val instances = ArrayList<BigListNav>()

		var labeler: ((Any) -> String)? = null

		fun showItem(index: Int, stopLive: Boolean) {

			if (stopLive) {
				live = false
			}

			this.index = index

			updateAll()
			for (instance in instances) {
				instance.onShow(index)
			}
		}

		fun setLive(value: Boolean) {
			live = value
			if (value) {
				// turning on live mode, go to the newest item
				items.size
					.takeIf { it > 0 }
					?.let { showItem(it - 1, false) }
			} else {
				updateAll()
			}
		}

		private fun updateAll() {
			for (instance in instances) {
				instance.update()
			}
		}

		fun newItem() {
			if (live) {
				val index = (items.size - 1)
					.takeIf { it >= 0 }
					?: return
				showItem(index, false)
			} else {
				updateAll()
			}
		}

		fun cleared() {
			index = null
			live = true
			updateAll()
		}

		fun reshow() {
			val index = index
				?: return
			showItem(index, false)
		}
	}

	var labeler: ((Any) -> String)?
		get() = core.labeler
		set(value) { core.labeler = value }

	val currentIndex: Int?
		get() = core.index

	private val navFirst = button("", icon = "fas fa-step-backward").apply {
		onClick {
			if (core.items.isNotEmpty()) {
				core.showItem(0, true)
			}
		}
		enabled = false
	}

	private val navBack100 =
		if (core.has100) {
			button("100", icon = "fas fa-angle-double-left").apply {
				onClick {
					core.index
						?.let { core.showItem(it - 100, true) }
				}
				enabled = false
			}
		} else {
			null
		}

	private val navBack10 = button("10", icon = "fas fa-angle-double-left").apply {
		onClick {
			core.index
				?.let { core.showItem(it - 10, true) }
		}
		enabled = false
	}

	private val navBack = button("1", icon = "fas fa-angle-left").apply {
		onClick {
			core.index
				?.let { core.showItem(it - 1, true) }
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
			core.index
				?.let { core.showItem(it + 1, true) }
		}
		enabled = false
	}

	private val navForward10 = button("10", icon = "fas fa-angle-double-right").apply {
		onClick {
			core.index
				?.let { core.showItem(it + 10, true) }
		}
		enabled = false
	}

	private val navForward100 =
		if (core.has100) {
			button("100", icon = "fas fa-angle-double-right").apply {
				onClick {
					core.index
						?.let { core.showItem(it + 100, true) }
				}
				enabled = false
			}
		} else {
			null
		}

	private val navLast = button("", icon = "fas fa-step-forward").apply {
		onClick {
			core.items.size
				.takeIf { it > 0 }
				?.let { core.showItem(it - 1, true) }
		}
		enabled = false
	}

	private val navLive = checkBox(true, label = "Watch").apply {
		style = CheckBoxStyle.PRIMARY
		onEvent {
			change = {
				core.setLive(value)
			}
		}
	}

	val live: Boolean get() =
		core.live

	init {
		core.instances.add(this)
		update()
	}

	fun showItem(index: Int, stopLive: Boolean) {

		// validate the index
		if (index < 0 || index >= core.items.size) {
			return
		}

		core.showItem(index, stopLive)
	}

	/** call this to update the UI after adding a new item to the list */
	fun newItem() {
		core.newItem()
	}

	/** call this to update the UI after adding clearing the items list */
	fun cleared() {
		core.cleared()
	}

	fun reshow() {
		core.reshow()
	}

	private fun update() {

		navFirst.enabled = core.index?.let { it > 0 } ?: false
		navBack100?.enabled = core.index?.let { it >= 100 } ?: false
		navBack10.enabled = core.index?.let { it >= 10 } ?: false
		navBack.enabled = core.index?.let { it > 0 } ?: false

		writeIndex()
		navCount.content = core.items
			.takeIf { it.isNotEmpty() }
			?.let { indexLabel(it.indices.last) }

		navForward.enabled = core.index?.let { it + 1 < core.items.size } ?: false
		navForward10.enabled = core.index?.let { it + 10 < core.items.size } ?: false
		navForward100?.enabled = core.index?.let { it + 100 < core.items.size } ?: false
		navLast.enabled = core.index?.let { it != core.items.size - 1 } ?: false

		navLive.value = core.live
	}

	private fun indexLabel(index: Int): String =
		// look for a label from the labeler first
		labeler?.invoke(core.items[index])
			// otherwise, just use the index as the label
			?: "${index + 1}"

	private fun writeIndex() {
		navIndex.value =
			core.index?.let { indexLabel(it) }
	}

	private fun readIndex(): Int? {

		val value = navIndex.value
			?: return null

		// look for the value in the list of labels
		return core.items.indices
			.map { indexLabel(it) }
			.indexOfFirst { it == value }
			.takeIf { it >= 0 }
	}

	private fun onIndex() {

		// read the new index, if possible
		val newIndex = readIndex()

		if (newIndex == null) {
			// invalid, restore the old index
			writeIndex()
			return
		}

		// apply the new index
		core.showItem(newIndex, true)
	}

	/**
	 * Creates another instance of this control that shares the same state,
	 * but can call a different callback function when the value changes.
	 *
	 * Useful for sharing the same control across different tabs with each tab showing different content.
	 */
	fun clone(onShow: (Int) -> Unit = {}): BigListNav =
		BigListNav(core, onShow)
}
