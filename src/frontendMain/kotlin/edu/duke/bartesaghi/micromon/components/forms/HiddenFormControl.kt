package edu.duke.bartesaghi.micromon.components.forms

import io.kvision.form.*
import io.kvision.html.Span


abstract class HiddenFormControl<T> : Span(), FormControl, GenericFormComponent<T> {

	companion object {
		private var counter = 0
	}

	private val idc = "hidden_form_control_${counter++}"

	override val flabel: FieldLabel = FieldLabel(idc, "", false)

	override val input: FormInput = object : FormInput, Span() {
		override var disabled: Boolean = false
		override var name: String? = null
		override var size: InputSize? = null
		override var validationStatus: ValidationStatus? = null
		override fun blur() {}
		override fun focus() {}
	}

	override val invalidFeedback: InvalidFeedback = InvalidFeedback().apply { visible = false }

	override fun blur() {}
	override fun focus() {}

	override fun subscribe(observer: (T) -> Unit): () -> Unit {
		throw Error("not implemented")
	}
}

class HiddenString(override var value: String? = null) : HiddenFormControl<String?>(), StringFormControl
