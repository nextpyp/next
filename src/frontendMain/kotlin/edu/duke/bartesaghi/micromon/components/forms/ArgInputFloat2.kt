package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.pyp.Arg
import edu.duke.bartesaghi.micromon.pyp.ArgValue
import io.kvision.core.onEvent
import io.kvision.form.*
import io.kvision.form.spinner.SpinnerInput
import io.kvision.html.Div


class ArgInputFloat2(override val arg: Arg) : ArgInputControl, Div() {

	companion object {
		var counter = 0
	}

	private val idc = "arg_input_float2_${counter++}"
	override val flabel: FieldLabel
		= FieldLabel(idc, "ignored")

	private val xSpinner = SpinnerInput()
		.apply {
			id = idc
		}
	private val ySpinner = SpinnerInput()

	override val labelTarget: String? get() = xSpinner.id

	override val invalidFeedback: InvalidFeedback
		= InvalidFeedback().apply { visible = false }

	override fun blur() = input.blur()
	override fun focus() = input.focus()

	private inner class Input : Div(), FormInput {

		private val control = this@ArgInputFloat2

		override var disabled: Boolean
			get() = control.xSpinner.disabled
			set(value) {
				control.xSpinner.disabled = value
				control.ySpinner.disabled = value
			}

		override var name: String? = control.arg.fullId

		override var size: InputSize?
			get() = control.xSpinner.size
			set(value) {
				control.xSpinner.size = value
				control.ySpinner.size = value
			}
		override var validationStatus: ValidationStatus? = null

		override fun blur() {
			control.xSpinner.blur()
			control.ySpinner.blur()
		}

		override fun focus() {
			control.xSpinner.focus()
			// just focus one spinner
		}
	}

	override val input: FormInput = run {
		val i = Input()
		i.add(xSpinner)
		i.add(ySpinner)
		i
	}
	init {
		addInternal(input)
	}

	override var name: String? = input.name

	override fun getValue(): Pair<Double,Double>? {
		val x = xSpinner.value?.toDouble() ?: return null
		val y = ySpinner.value?.toDouble() ?: return null
		return x to y
	}

	override fun getValueAsString() =
		getValue()?.let { (x, y) -> "$x, $y" } ?: ""

	override fun setValue(v: Any?) {
		when (v) {
			is Pair<*,*> -> {
				val (x, y) = v
				xSpinner.value = x as? Number?
				ySpinner.value = y as? Number?
			}
			else -> {
				xSpinner.value = null
				ySpinner.value = null
			}
		}
	}

	val default: Pair<Double,Double>? get() = when (arg.default) {
		is ArgValue.VRef -> (sourceControlOrThrow as ArgInputFloat2).getValue()
		is ArgValue.VFloat2 -> arg.default.value
		else -> null
	}

	override var argValue: ArgValue?
		get() {
			val x = xSpinner.value ?: return null
			val y = ySpinner.value ?: return null
			return ArgValue.VFloat2(x.toDouble() to y.toDouble())
		}
		set(v) {
			val pair = (v as? ArgValue.VFloat2)?.value ?: default
			xSpinner.value = pair?.first
			ySpinner.value = pair?.second
		}

	override fun isDefault(): Boolean =
		getValue() == default

	override var sourceControl: ArgInputControl? = null
	override var destControl : ArgInputControl? = null

	override var onChange: (() -> Unit)? = null

	init {
		xSpinner.onEvent {
			change = {

				// enforce the invariant: x,y should both be null, or both not be null
				if (xSpinner.value == null) {
					ySpinner.value = null
				} else if (xSpinner.value != null && ySpinner.value == null) {
					ySpinner.value = 0.0
				}

				onChange?.invoke()
			}
		}
		ySpinner.onEvent {
			change = {

				// enforce the invariant: x,y should both be null, or both not be null
				if (ySpinner.value == null) {
					xSpinner.value = null
				} else if (ySpinner.value != null && xSpinner.value == null) {
					xSpinner.value = 0.0
				}

				onChange?.invoke()
			}
		}
	}
}
