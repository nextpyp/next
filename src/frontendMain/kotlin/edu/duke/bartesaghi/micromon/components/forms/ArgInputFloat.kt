package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.pyp.Arg
import edu.duke.bartesaghi.micromon.pyp.ArgValue
import io.kvision.core.onEvent
import io.kvision.form.spinner.ForceType
import io.kvision.form.spinner.Spinner
import io.kvision.form.text.Text


class ArgInputFloat(override val arg: Arg) : ArgInputControl, Text(
	name = arg.fullId,
) {

	val default: Double? get() = when (arg.default) {
		is ArgValue.VRef -> (sourceControlOrThrow as ArgInputFloat).value?.toDoubleOrNull()
		is ArgValue.VFloat -> arg.default.value
		else -> null
	}

	override var argValue: ArgValue?
		get() =
			value?.let { ArgValue.VFloat(it.toDouble()) }
		set(v) {
			value = ((v as? ArgValue.VFloat)?.value ?: default)?.toString()
		}

	override fun isDefault(): Boolean =
		value?.toDoubleOrNull() == default

	override var sourceControl: ArgInputControl? = null
	override var destControl : ArgInputControl? = null

	override var onChange: (() -> Unit)? = null

	init {
		onEvent {
			change = {
				sanitize()
				onChange?.invoke()
			}
		}
	}

	private fun sanitize() {

		val str = value ?: return

		// just try to parse as a float, or delete the value
		val f = str.toDoubleOrNull()

		value = f?.toString()
	}
}
