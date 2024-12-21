package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.invoke
import io.kvision.form.*
import io.kvision.panel.SimplePanel
import js.UnshadowedWidget
import kotlinx.browser.document
import kotlinx.html.dom.create
import kotlinx.html.js.select
import org.w3c.dom.HTMLSelectElement


open class NativeSelect(
	name: String? = null,
	value: String? = null,
	label: String? = null,
	init: (NativeSelect.() -> Unit)? = null
) : SimplePanel(setOf("form-group")), StringFormControl {

	companion object {
		internal var counter = 0
	}

	private val idc = "kv_form_native_select_${counter++}"
	final override val input = NativeSelectInput(
		name,
		value,
		classes = setOf("form-control")
	)
	final override val flabel: FieldLabel = FieldLabel(idc, label, false, setOf("control-label"))
	final override val invalidFeedback: InvalidFeedback =
		InvalidFeedback().apply { visible = false }

	init {
		input.id = idc
		input.eventTarget = this
		this.addPrivate(flabel)
		this.addPrivate(input)
		this.addPrivate(invalidFeedback)
		init?.invoke(this)
	}

	// delegate implementations to the input
	override var name: String?
		get() = input.name
		set(value) { input.name = value }
	override var value: String?
		get() = input.value
		set(value) { input.value = value }
	final override var disabled: Boolean
		get() = input.disabled
		set(value) { input.disabled = value }
	override fun subscribe(observer: (String?) -> Unit): () -> Unit =
		input.subscribe(observer)
}


open class NativeSelectInput(
	name: String? = null,
	value: String? = null,
	classes: Set<String> = emptySet(),
	init: (NativeSelectInput.() -> Unit)? = null
) : UnshadowedWidget(classes), GenericFormComponent<String?>, FormInput {

	// add a real honest browser-native HTMLSelectElement
	val select: HTMLSelectElement = document.create.select(classes = "native")
	init {
		elem.appendChild(select)
	}

	final override var name: String?
		get() = select.name
		set(value) { select.name = value ?: "" }
	final override var value: String?
		get() = select.value
			.takeIf { it.isNotEmpty() }
		set(value) { select.value = value ?: "" }
	final override var disabled: Boolean
		get() = select.disabled
		set(value) { select.disabled = value }

	// make no-op implementations for stuff we don't care about
	override fun subscribe(observer: (String?) -> Unit): () -> Unit = {}
	override var size: InputSize?
		get() = InputSize.SMALL
		set(_) {}
	override var validationStatus: ValidationStatus?
		get() = null
		set(_) {}

	init {
		this.name = name
		this.value = value
		this.id?.let { select.id = it }
		init?.invoke()
	}
}
