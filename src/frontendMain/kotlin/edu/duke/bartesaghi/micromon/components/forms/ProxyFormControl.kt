package edu.duke.bartesaghi.micromon.components.forms

import io.kvision.form.*
import io.kvision.html.Span


abstract class ProxyFormControl<T>(override val input: FormInput) : Span(), FormControl, GenericFormComponent<T> {

	companion object {
		private var counter = 0
	}

	private val idc = "proxy_form_control_${counter++}"

	override val flabel: FieldLabel = FieldLabel(idc, "", false)

	override val invalidFeedback: InvalidFeedback = InvalidFeedback().apply { visible = false }

	override fun blur() {}
	override fun focus() {}

	override fun subscribe(observer: (T) -> Unit): () -> Unit {
		throw Error("not implemented")
	}
}


class ProxyStringControl(val target: StringFormControl) : ProxyFormControl<String?>(target.input), StringFormControl {

	override var value: String?
		get() = target.value
		set(value) { target.value = value }
}

fun StringFormControl.proxy(): ProxyStringControl =
	ProxyStringControl(this)
