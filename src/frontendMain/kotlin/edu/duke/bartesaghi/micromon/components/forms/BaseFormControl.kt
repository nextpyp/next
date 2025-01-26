package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.AppScope
import io.kvision.core.Component
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.form.*
import io.kvision.html.Div
import io.kvision.utils.obj
import org.w3c.dom.CustomEventInit


/**
 * Because thinking about enabled is much easier than thinking about disabled
 */
interface EnableableControl {
	var enabled: Boolean
}


/**
 * A utility class to cut down on boilerplate when making custom form controls for KVision forms.
 */
abstract class BaseFormControl<T:FormInput>(
	label: String? = null,
	classes: Set<String> = emptySet()
) : FormControl, EnableableControl, Div(classes = setOf("form-group") + classes) {

	abstract override val input: T
	abstract val labelReferent: Component

	companion object {
		var counter = 0
	}

	val uniqueControlId = "micromon_control_${counter++}"
	override val flabel: FieldLabel =
		FieldLabel(uniqueControlId, label)

	override val invalidFeedback: InvalidFeedback =
		InvalidFeedback().apply { visible = false }

	// forward the interfaces to the input control
	override var disabled: Boolean
		get() = input.disabled
		set(value) { input.disabled = value }

	override var enabled: Boolean
		get() = !disabled
		set(value) { disabled = !value }

	override fun blur() = input.blur()
	override fun focus() = input.focus()

	init {
		// layout
		// NOTE: On this constructor, `input` isn't assigned yet, so we can't use it.
		//       So defer layout until after the constructor of the subclass by launching a task onto the event queue
		AppScope.launch {
			// assign the label id to a control
			labelReferent.setAttribute("id", uniqueControlId)
			addInternal(flabel)
			addInternal(input)
		}
	}

	protected fun forwardChangeEventsFrom(src: Widget) {
		val self = this
		src.onEvent {
			change = { event ->
				self.getSnOn()?.change?.invoke(event)
			}
		}
	}
}


/**
 * A utility class to cut down on boilerplate when making custom form controls for KVision forms.
 */
abstract class BaseFormInput(
	override var name: String? = null,
	classes: Set<String> = emptySet()
) : FormInput, EnableableControl, Div(classes = classes) {

	override var size: InputSize? = null
	override var validationStatus: ValidationStatus? = null

	override var disabled: Boolean
		get() = !enabled
		set(value) { enabled = !value }

	protected fun sendChangeEvent(value: Any?) {
		dispatchEvent("change", obj<CustomEventInit> { detail = value })
	}

	protected fun forwardChangeEventsFrom(src: Widget) {
		val self = this
		src.onEvent {
			change = { event ->
				self.getSnOn()?.change?.invoke(event)
			}
		}
	}
}
