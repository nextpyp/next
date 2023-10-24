package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.ServerVal
import edu.duke.bartesaghi.micromon.services.Services
import io.kvision.core.onEvent
import io.kvision.form.select.Select


class ArgInputClusterQueue(override val arg: Arg) : ArgInputControl, Select(
	name = arg.fullId,
	options = emptyList()
) {

	companion object {

		val queues = ServerVal {
			Services.forms.clusterQueues()
		}
	}

	val type = (arg.input as ArgInput.ClusterQueue).group

	private var queues: List<String> = emptyList()
	private var default: String? = null
	init {
		// we don't have queues yet, but load them ASAP
		AppScope.launch {
			queues = Companion.queues.get()[type]
			default = queues.firstOrNull()

			// update the control
			options = queues.map { it to it }
			when (value) {

				null -> value = default

				!in queues -> {
					// if there's a bad queue name stuck in the args, reset it to default
					// and send a change event so the ArgsValues gets updated
					value = default
					onChange?.invoke()
				}

				// we can't set a value, but trigger the change event anyway
				// so the default/specified flag gets updated in the form
				else -> onChange?.invoke()
			}
		}
	}

	override var argValue: ArgValue?
		get() =
			value?.let { ArgValue.VStr(it) }
		set(v) {
			value = (v as? ArgValue.VStr)?.value ?: default
		}

	override fun isDefault(): Boolean =
		value == default

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
