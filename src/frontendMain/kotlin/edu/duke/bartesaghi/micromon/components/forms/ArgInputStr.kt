package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.pyp.Arg
import edu.duke.bartesaghi.micromon.pyp.ArgValue
import io.kvision.core.onEvent
import io.kvision.form.text.Text


class ArgInputStr(override val arg: Arg) : ArgInputControl, Text(
	name = arg.fullId
) {

	val default get() = when (arg.default) {
		is ArgValue.VRef -> (sourceControlOrThrow as ArgInputStr).value
		is ArgValue.VStr -> arg.default.value
		else -> null
	}

	override var argValue: ArgValue?
		get() =
			value?.takeIf { it.isNotBlank() }?.let { ArgValue.VStr(it) }
		set(v) {
			value = (v as? ArgValue.VStr)?.value?.takeIf { it.isNotBlank() } ?: default
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
			change = {
				onChange?.invoke()
			}
		}
	}
}
