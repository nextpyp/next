package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.AppScope
import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.html.Div
import io.kvision.panel.TabPanel
import js.getHTMLElement
import kotlin.reflect.KMutableProperty0


/**
 * A tab panel that only creates tab content when the tab is first shown.
 * (The KVision tab panel creates all tab content at startup)
 * Useful for tabs that are expensive to create the first time, but may never be seen.
 */
class LazyTabPanel(
	classes: Set<String> = setOf()
) : Div(classes = classes) {

	inner class LazyTab(val id: Int, val init: (LazyTab) -> Unit) {

		val elem = Div()

		private var needsInit = true
		var onActivate: () -> Unit = { }

		fun activate() {
			if (needsInit) {
				needsInit = false
				init(this)
			}
			onActivate()
		}

		fun show() {
			showTab(id)
		}
	}

	var persistence: KMutableProperty0<Int?>? = null

	private val tabs = ArrayList<LazyTab>()
	private val panel = TabPanel()
	private var initialized = false

	init {

		add(panel)

		// activate new tab after a tab change, if needed
		panel.onEvent {
			tabChange = tabChange@{ e ->
				val id = e.detail.data as? Int ?: return@tabChange

				// sometimes we get tab change events too early
				// only respond to them after initialization
				if (initialized) {
					persistence?.set(id)
					tabs[id].activate()
				}
			}
		}
	}

	fun addTab(title: String, icon: String? = null, init: (LazyTab) -> Unit): LazyTab {

		val index = tabs.size
		val tab = LazyTab(index, init)
		tabs.add(tab)

		// also add to the underlying tabs panel
		panel.addTab(title, tab.elem, icon)

		return tab
	}

	private fun defaultTab(): LazyTab? =
		// get the last saved tab, if any, or the first tab
		persistence
			?.get()
			?.let { tabs.getOrNull(it) }
			?: tabs.firstOrNull()

	fun initWithDefaultTab(tab: LazyTab? = defaultTab()) {

		tab ?: return

		initialized = true

		// activate the tab, but do it at the end of the event queue
		// otherwise, tabChange events can get lost in certain cases,
		// no idea why and I don't want to waste any more time debugging this
		// might be triggered when more DOM events get dispatched inside of a DOM event handler?
		// probably a bug in snabbdom? shadow DOM libraries all need to burn ...
		AppScope.launch {
			panel.activeIndex = tab.id
		}
	}

	fun showTab(tabId: Int): Boolean {

		// make sure the tab exists
		val tab = tabs.getOrNull(tabId)
			?: return false

		// if the tab is already active, do nothing
		if (panel.activeIndex == tab.id) {
			return false
		}

		// otherwise, activate the tab
		// NOTE: KVision tab panel will fire the change event even if the tab index dosen't actually change
		panel.activeIndex = tab.id

		return true
	}
}

fun Container.lazyTabPanel(block: LazyTabPanel.() -> Unit): LazyTabPanel {
	val panel = LazyTabPanel()
	add(panel)
	block(panel)
	panel.initWithDefaultTab()
	return panel
}

fun Container.flatLazyTabPanel(block: LazyTabPanel.() -> Unit): LazyTabPanel {
	val panel = LazyTabPanel(classes = setOf("flat-selector"))
	add(panel)
	block(panel)
	panel.initWithDefaultTab()
	return panel
}


/**
 * Some components can only be activated if they're in the real DOM,
 * but elements in tabs aren't in the real DOM when they're not showing,
 * so defer load events until the tab is actually showing
 */
class TabDataLoader<T> {

	var onData: ((T) -> Unit)? = null

	private var lazyTab: LazyTabPanel.LazyTab? = null
	private var data: T? = null
	private var loaded = false

	fun init(lazyTab: LazyTabPanel.LazyTab) {
		this.lazyTab = lazyTab
		lazyTab.onActivate = {
			loadIfPossible()
		}
	}

	fun setData(data: T) {
		this.data = data
		loadIfPossible()
	}

	private fun loadIfPossible() {
		val data = data
		val elem = lazyTab?.elem?.getHTMLElement()
		if (!loaded && data != null && elem != null) {
			loaded = true
			onData?.invoke(data)
		}
	}
}
