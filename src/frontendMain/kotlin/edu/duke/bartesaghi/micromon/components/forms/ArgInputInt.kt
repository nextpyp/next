package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.pyp.Arg
import edu.duke.bartesaghi.micromon.pyp.ArgValue
import io.kvision.core.onEvent
import io.kvision.form.spinner.Spinner


class ArgInputInt(override val arg: Arg) : ArgInputControl, Spinner(
	name = arg.fullId,
	decimals = 0,
	step = 1.0
) {

	val default get() = when (arg.default) {
		is ArgValue.VRef -> (sourceControlOrThrow as ArgInputInt).value
		is ArgValue.VInt -> arg.default.value
		else -> null
	}

	override var argValue: ArgValue?
		get() =
			value?.let { ArgValue.VInt(it.toLong()) }
		set(v) {
			value = (v as? ArgValue.VInt)?.value ?: default
		}

	override fun isDefault(): Boolean =
		value?.toLong() == default?.toLong()
		// Kotlin/JS can use different integral number representations (eg, int, long) that apparently don't handle equality well
		// looks like int(1) == long(1) can actually return false?
		// so explicitly convert to the same concrete type before comparison

	override var sourceControl: ArgInputControl? = null
	override var destControl : ArgInputControl? = null

	override var onChange: (() -> Unit)? = null

	init {
		onEvent {
			change = {
				onChange?.invoke()
			}
		}
	}
}
