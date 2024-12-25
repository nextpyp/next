package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.ServerVal
import edu.duke.bartesaghi.micromon.services.Services
import io.kvision.core.onEvent
import kotlinx.html.dom.append
import kotlinx.html.option


class ArgInputClusterTemplate(override val arg: Arg) : ArgInputControl, NativeSelect(
	name = arg.fullId
) {

	companion object {

		val clusterTemplates = ServerVal {
			Services.forms.clusterTemplates()
		}
	}

	private val savedValue = ArrayList<String?>()
	private var isInit = false

	init {

		addCssClass("cluster-templates")

		// we don't have options yet, but load them ASAP
		AppScope.launch {

			val templates = clusterTemplates.get()

			// add the options
			for (template in templates) {
				input.select.append {
					option {
						value = template.path
						+ "${template.title} - ${template.description}"
					}
				}
			}

			// apply any saved values now
			isInit = true
			if (savedValue.isNotEmpty()) {

				val v = savedValue[0]
				savedValue.clear()

				if (templates.none { it.path == v }) {
					// if there's a bad template stuck in the args, reset it to default
					value = null
				} else {
					value = v
				}

				// and send a change event so the ArgsValues gets updated
				onChange?.invoke()
			}
		}
	}

	override var argValue: ArgValue?
		get() =
			value?.let { ArgValue.VStr(it) }
		set(v) {
			val str = (v as? ArgValue.VStr)?.value
			// if we haven't loaded the options yet, save the value for later
			if (!isInit) {
				savedValue.clear()
				savedValue.add(str)
			} else {
				value = str
			}
		}

	override fun isDefault(): Boolean =
		value == null

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
