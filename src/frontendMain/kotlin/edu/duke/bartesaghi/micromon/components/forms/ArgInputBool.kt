package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.pyp.Arg
import edu.duke.bartesaghi.micromon.pyp.ArgValue
import io.kvision.core.onEvent
import io.kvision.form.check.CheckBox
import io.kvision.form.check.CheckBoxStyle

class ArgInputBool(override val arg: Arg) : ArgInputControl, CheckBox(
	name = arg.fullId
) {

	val default get() = when (arg.default) {
		is ArgValue.VRef -> (sourceControlOrThrow as ArgInputBool).value
		is ArgValue.VBool -> arg.default.value
		else -> null
	}

	override var argValue: ArgValue?
		get() =
			ArgValue.VBool(value)
		set(v) {
			value = (v as? ArgValue.VBool)?.value ?: default ?: false
		}

	override fun isDefault(): Boolean =
		value == default

	override var sourceControl: ArgInputControl? = null
	override var destControl : ArgInputControl? = null

	override var onChange: (() -> Unit)? = null

	override var enabled: Boolean
		get() = !disabled
		set(value) { disabled = !value }

	init {
		onEvent {
			style = CheckBoxStyle.PRIMARY;
			change = {
				onChange?.invoke()
			}
		}
	}
}
