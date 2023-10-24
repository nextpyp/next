package edu.duke.bartesaghi.micromon.components.forms

import io.kvision.form.*
import io.kvision.html.Div


/**
 * A utility class to cut down on boilerplate when making custom form controls for KVision forms.
 */
abstract class BaseFormControl(
	override var name: String? = null,
	label: String? = null,
	classes: Set<String> = emptySet()
) : FormControl, Div(classes = classes) {

	companion object {
		var counter = 0
	}

	private val idc = "micromon_control_${counter++}"
	override val flabel: FieldLabel
		= FieldLabel(idc, label)

	final override val input: FormInput = object : FormInput, Div() {

		override var disabled: Boolean = false
		override var size: InputSize? = null
		override var validationStatus: ValidationStatus? = null

		private val base get() = this@BaseFormControl

		override var name: String?
			get() = base.name
			set(value) { base.name = value }

		override fun blur() = base.blur()
		override fun focus() = base.focus()

		init {
			id = base.idc
			base.addInternal(this)
		}
	}

	override val invalidFeedback: InvalidFeedback
		= InvalidFeedback().apply { visible = false }

	abstract override var disabled: Boolean
	abstract override fun blur()
	abstract override fun focus()
}
