package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.pyp.Arg
import edu.duke.bartesaghi.micromon.pyp.ArgType
import edu.duke.bartesaghi.micromon.pyp.ArgValue
import io.kvision.core.onEvent
import io.kvision.form.select.Select


class ArgInputEnum(override val arg: Arg) : ArgInputControl, Select(
	name = arg.fullId,
	options = (arg.type as ArgType.TEnum).values.map { it.id to it.name }
) {

	val default get() = when (arg.default) {
		is ArgValue.VRef -> (sourceControlOrThrow as ArgInputEnum).value
		is ArgValue.VEnum -> arg.default.value
		else -> null
	}

	override var argValue: ArgValue?
		get() =
			value?.let { ArgValue.VEnum(it) }
		set(v) {
			value = (v as? ArgValue.VEnum)?.value ?: default
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
