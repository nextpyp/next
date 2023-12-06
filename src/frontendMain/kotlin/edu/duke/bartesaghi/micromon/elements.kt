package edu.duke.bartesaghi.micromon

import com.github.snabbdom.VNode
import edu.duke.bartesaghi.micromon.services.ImageSize
import js.react.ReactBuilder
import io.kvision.core.*
import io.kvision.html.*
import io.kvision.html.Image
import io.kvision.modal.Alert
import io.kvision.navbar.*
import io.kvision.navbar.Nav
import io.kvision.panel.SimplePanel
import io.kvision.toast.Toast
import js.getHTMLElement
import kotlinext.js.jsObject
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import org.w3c.dom.*
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.url.URL
import org.w3c.fetch.RELOAD
import org.w3c.fetch.RequestCache
import org.w3c.fetch.Response
import kotlin.reflect.KProperty


// See FontAwesome icons:
// https://fontawesome.com/icons?d=gallery&m=free

fun Container.spinnerIcon() =
	iconStyled("fas fa-spinner fa-pulse")

fun Container.loading(
	text: String? = null,
	inline: Boolean = false,
	classes: Set<String> = setOf()
): Container {
	val allClasses = classes + setOf("loading")
	val outer = if (inline) {
		span(classes = allClasses)
	} else {
		div(classes = allClasses)
	}
	outer.apply {
		spinnerIcon()
		if (text != null) {
			span(text)
		}
	}
	return outer
}

fun Container.errorMessage(msg: String) =
	div(classes = setOf("error-message")) {
		iconStyled("fas fa-exclamation-circle")
		span(msg)
	}

fun Container.errorMessage(t: Throwable) =
	errorMessage(t.message ?: "Unknown error")

fun Toast.errorMessage(t: Throwable, msg: String? = null) {
	val errmsg = t.message
	if (msg != null && errmsg != null) {
		error("$msg: $errmsg")
	} else {
		error(msg ?: errmsg ?: "Unknown error")
	}
}

fun ReactBuilder.errorMessage(msg: String) =
	div(className = "error-message") {
		icon("fas fa-exclamation-circle")
		span(msg)
	}

fun Container.emptyMessage(msg: String) {
	div(msg, classes = setOf("empty", "spaced"))
}

fun Throwable.alert() {
	Alert.show("Error", message ?: "Unknown error")
}


/**
 * Re-implement the Navbar class so we can modify the 'brand' area
 */
class NavbarEx(
	classes: Set<String> = setOf(),
	init: (NavbarEx.() -> Unit)? = null
) : SimplePanel(classes) {

	private val idc = "navbarex_$counter"

	val brand = Span(classes = setOf("navbar-brand"))
	private val links = SimplePanel(setOf("collapse", "navbar-collapse")) {
		id = idc
	}

	init {
		addInternal(brand)
		addInternal(NavbarExButton(idc))
		addInternal(links)
		counter++
		init?.invoke(this)
	}

	override fun render(): VNode {
		return render("nav", childrenVNodes())
	}

	override fun add(child: Component): NavbarEx {
		links.add(child)
		return this
	}

	override fun addAll(children: List<Component>): NavbarEx {
		links.addAll(children)
		return this
	}

	override fun remove(child: Component): NavbarEx {
		links.remove(child)
		return this
	}

	override fun removeAll(): NavbarEx {
		links.removeAll()
		return this
	}

	override fun getChildren(): List<Component> {
		return links.getChildren()
	}

	override fun buildClassSet(classSetBuilder: ClassSetBuilder) {
		super.buildClassSet(classSetBuilder)
		classSetBuilder.add("navbar")
		classSetBuilder.add(NavbarExpand.LG.className)
		classSetBuilder.add("navbar-light")
		classSetBuilder.add(BsBgColor.LIGHT.className)
	}

	companion object {
		internal var counter = 0
	}

	val nav: Nav? get() =
		getChildren()
			.filterIsInstance<Nav>()
			.firstOrNull()
}

fun Container.navbarEx(
	classes: Set<String> = setOf(),
	init: (NavbarEx.() -> Unit)? = null
): NavbarEx {
	val navbar = NavbarEx(classes, init)
	this.add(navbar)
	return navbar
}

internal class NavbarExButton(private val idc: String, private val toggle: String = "Toggle navigation") :
	SimplePanel(setOf("navbar-toggler")) {

	init {
		span(classes = setOf("navbar-toggler-icon"))
	}

	override fun render(): VNode {
		return render("button", childrenVNodes())
	}

	override fun buildAttributeSet(attributeSetBuilder: AttributeSetBuilder) {
		super.buildAttributeSet(attributeSetBuilder)
		attributeSetBuilder.add("type", "button")
		attributeSetBuilder.add("data-toggle", "collapse")
		attributeSetBuilder.add("data-target", "#$idc")
		attributeSetBuilder.add("aria-controls", idc)
		attributeSetBuilder.add("aria-expanded", "false")
		attributeSetBuilder.add("aria-label", toggle)
	}
}

fun NavbarEx.navex(
	rightAlign: Boolean = false,
	classes: Set<String> = setOf(),
	init: (Nav.() -> Unit)? = null
): Nav {
	val nav = Nav(rightAlign, classes).apply { init?.invoke(this) }
	this.add(nav)
	return nav
}


/** you know... one of those click-the-icon-to-make-the-bottom-bit-slide-out things */
class Disclosure(
	label: Container.() -> Unit = {},
	disclosed: Container.() -> Unit = {},
	classes: Set<String> = emptySet()
) : Div(classes = setOf("disclosure") + classes) {

	private val iconElem: IconStyled
	val labelElem: Div
	val disclosedElem: Div

	init {

		val me = this

		iconElem = IconStyled("")
		labelElem = Div(classes = setOf("disclosure-label")) {
			label.invoke(this)
		}
		disclosedElem = Div(classes = setOf("disclosure-disclosed")) {
			disclosed.invoke(this)
		}

		// layout the elems
		div(classes = setOf("disclosure-icon")) {
			add(me.iconElem)
		}
		div(classes = setOf("disclosure-content")) {
			add(me.labelElem)
			add(me.disclosedElem)
		}

		// wire up events
		iconElem.onClick { toggle() }
		labelElem.onClick { toggle() }
	}

	var open: Boolean
		get() = disclosedElem.visible
		set(value) {
			iconElem.icon = when (value) {
				true -> "fas fa-chevron-down"
				false -> "fas fa-chevron-right"
			}
			disclosedElem.visible = value
		}

	var iconVisible: Boolean
		get() = iconElem.visible
		set(value) { iconElem.visible = value }

	fun toggle() {
		open = !open
	}

	init {
		open = true
	}
}

fun Container.disclosure(label: Container.() -> Unit = {}, disclosed: Container.() -> Unit = {}): Disclosure {
	val disclosure = Disclosure(label, disclosed)
	this.add(disclosure)
	return disclosure
}


/**
 * An Icon wrapper with support for static styles
 * For consistency, use this instead of KVision's Icon() class.
 *
 * NOTE: don't try to shadow KVisons `Icon` class name,
 * that makes it too confusing to know which icon class is being used when reading the code
 */
open class IconStyled(icon: String, val classes: Set<String> = emptySet()) : Icon(icon) {

	override fun buildClassSet(classSetBuilder: ClassSetBuilder) {
		super.buildClassSet(classSetBuilder)
		icon.split(" ").forEach { classSetBuilder.add(it) }
		classes.forEach { classSetBuilder.add(it) }
	}
}

/**
 * DSL builder function for the styled icon.
 * For consistency, use this instead of KVision's icon() function.
 *
 * NOTE: don't try to shadow KVisons `icon` function name,
 * that makes it too confusing to know which icon function is being called when reading the code
 */
fun Container.iconStyled(
	icon: String, classes: Set<String> = emptySet(), init: (IconStyled.() -> Unit)? = null
): IconStyled {
	val elem = IconStyled(icon, classes + setOf("icon")).apply { init?.invoke(this) }
	this.add(elem)
	return elem
}


/**
 * How does KVision not have a basic list builder??
 */
open class UnorderedList(
	classes: Set<String> = setOf(),
	init: (Tag.() -> Unit)? = null
) : Tag(
	TAG.UL,
	classes = classes,
	init = init
)


fun Container.unorderedList(
	classes: Set<String> = setOf(),
	init: (UnorderedList.() -> Unit)? = null
) =
	UnorderedList(classes)
		.also {
			init?.invoke(it)
			add(it)
		}


fun UnorderedList.element(
	content: String? = null,
	classes: Set<String> = setOf(),
	init: (Tag.() -> Unit)? = null
) =
	Tag(TAG.LI, content = content, classes = classes, init = init)
		.also { add(it) }



fun HTMLElement.removeAllChildren() {
	children
		.asList()
		.toList() // asList is a view into the collection, so copy into a new list before destroying it with remove()
		.forEach { it.remove() }
}

val HTMLElement.childElements: List<HTMLElement> get() =
	(0 until childElementCount)
		.mapNotNull { childNodes[it] as? HTMLElement }

/**
 * simple DFS on the DOM
 * Returns the first descendent node that satisfies the predicate, or null
 */
fun HTMLElement.walk(predicate: (HTMLElement) -> Boolean): HTMLElement? {

	val root = this

	val elems = ArrayList<HTMLElement>()
	elems.add(root)

	while(elems.isNotEmpty()) {

		val elem = elems.removeLast()

		if (elem !== root && predicate(elem)) {
			return elem
		}

		elems.addAll(elem.childElements)
	}

	return null
}


inline fun <reified T:Widget> T.onClick(noinline handler: T.(MouseEvent) -> Unit): T {
	val self = this
	onEvent {
		this.click = { e ->
			self.handler(e)
		}
	}
	return this
}


class Popover(val widget: Widget, val container: HTMLElement) {

	fun onInserted(block: (Event) -> Unit) {
		widget.getElementJQueryD().on("inserted.bs.popover", block)
	}

	/**
	 * Tries to find the popover element created by the JS library
	 * Might not find it perfectly though
	 *
	 * If the popover is not showing, this will return null
	 */
	fun popoverElement(): HTMLElement? =
		container
			.childElements
			.filter { it.id.startsWith("popover") }
			.apply {
				if (size > 1) {
					throw Error("multiple popover elements found, can't pick one")
				}
			}
			.firstOrNull()
}

/**
 * The built-in KVision popover implementation leaves much to be desired =(
 */
inline fun <reified T:Widget> T.popover(
	placement: Placement? = null,
	triggers: Set<Trigger>? = null,
	offset: Pair<Number,Number>? = null,
	block: T.() -> HTMLElement
): Popover? {

	// if this element isn't in the real DOM yet, the popover function won't work
	// try to fail gracefully
	if (getElementJQueryD()?.popover == undefined) {
		console.warn("can't get popover function")
		return null
	}

	val widget = this

	// focus-triggered popovers need a tab index to become focusable
	if (triggers != null && Trigger.FOCUS in triggers) {
		if (widget.tabindex == null) {
			widget.tabindex = 9999
		}
	}

	val parentElem = widget.parent?.getHTMLElement()
		?: throw Error("widget has no parent HTML element")

	// https://getbootstrap.com/docs/4.0/components/popovers/
	getElementJQueryD()?.popover(jsObject {

		this.animation = true
		this.title = widget.title
		this.content = block()
		this.html = true

		// attach the popover to the widget parent,
		// to keep it from inheriting any transforms of the widget
		// (like a spinning loading icon =P )
		this.container = parentElem

		// optional settings
		placement?.let { this.placement = it.name.lowercase() }
		triggers?.let { this.trigger = it.joinToString(" ") { trigger -> trigger.name.lowercase() } }
		offset?.let { this.offset = "${it.first},${it.second}" }

		// keep the popover library from eating the title attribute on our widget!
		// see: https://github.com/twbs/bootstrap/issues/15359#issuecomment-356936914
		this.selector = true
	})

	return Popover(this, parentElem)
}

inline fun <reified T:Widget> T.closePopover() {

	if (getElementJQueryD()?.popover == undefined) {
		console.warn("can't get popover function")
		return
	}

	getElementJQueryD()?.popover("hide")
}


/**
 * Force the browser to download an image and update its cache.
 * Although, in practice, relying on the browser cache to have a certain state is Very unreliable!
 * Use HTMLImageElement.refresh() instead.
 */
suspend fun refreshImage(url: String): Response =
	window.fetch(url, jsObject {
		cache = RequestCache.RELOAD
	}).await()


/**
 * Forces the browser to download a new image and display it.
 */
suspend fun HTMLImageElement.refresh() {

	// the URL we want to reload is the image's current src attribute
	val url = src

	// fetch the image url out-of-band and force the browser to update its cache
	val response = refreshImage(url)

	// NOTE: this does not work!
	//src = url
	// even though the browser cache has supposedly been updated,
	// the browser will *still* not display the new image!

	// update the element with the image obtained directly from the response
	src = URL.createObjectURL(response.blob().await())
}


const val dynamicImageClassName = "dynamic-image"

/**
 * Refreshes all subtree images tagged with the dynamic image CSS class
 */
fun HTMLElement.refreshDynamicImages() {
	AppScope.launch {
		val img = querySelector(".$dynamicImageClassName")
			as? HTMLImageElement
		img?.refresh()
	}
}


fun Widget.flash(numFlashes: Int = 3) =
	getHTMLElement()?.flash(numFlashes)

fun Element.flash(numFlashes: Int = 3) {

	// scroll the element into view
	scrollIntoView()

	val elem = this

	AppScope.launch {
		"highlighted".let {
			for (i in 0 until numFlashes) {
				elem.classList.add(it)
				delay(300)
				elem.classList.remove(it)
				delay(200)
			}
		}
	}
}

fun Image.ifNonExistentUsePlaceholder(imageSize: ImageSize) {
	val img = this
	onEvent {
		error = {
			img.src = "/img/placeholder/${imageSize.id}"
		}
	}
}


class HtmlIntAttribute(val name: String) {

	operator fun getValue(self: Component, property: KProperty<*>): Int? =
		self.getAttribute(name)?.toInt()

	operator fun setValue(self: Component, property: KProperty<*>, value: Int?) {
		if (value != null) {
			self.setAttribute(name, value.toString())
		} else {
			self.removeAttribute(name)
		}
	}
}

// weird, no KVision accessors for basic table attributes?
var Td.colspan by HtmlIntAttribute("colspan")
var Td.rowspan by HtmlIntAttribute("rowspan")
