package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.AppScope
import io.kvision.core.onEvent
import io.kvision.form.select.SelectOption
import io.kvision.form.select.SelectRemote
import io.kvision.form.select.SelectRemoteInput
import io.kvision.remote.RemoteOption


/**
 * At creation time, no select options will have been loaded from the server yet,
 * so no value will be selected as the default.
 * As soon as options become loaded, pick the first one as the default selection.
 */
fun SelectRemoteInput<*>.setFirstAsDefault() {
	val sel = this
	onEvent {
		refreshedBsSelect = {
			// after the items are loaded from the server
			if (sel.value == null) {
				sel.value = sel.getChildren()
					.filterIsInstance<SelectOption>()
					.firstOrNull()
					?.value
			}
		}
	}
}


/**
 * Tragically, RemoteSelect(Input) doesn't correctly select the current value by default
 * I guess it doesn't have any options from the server yet? Whatever ...
 * This function tries to fix that.
 */
fun SelectRemote<*>.lookupDefault(looker: suspend (String) -> List<RemoteOption>) {

	val select = input
	select.onEvent {
		loadedBsSelect = e@{

			// if we have a value, make sure there's a selected option
			// because somehow the control dosen't do this already!! ARGHH!!!
			val value = select.value
				?: return@e

			// see if there's an option already
			val option = select.getChildren()
				.filterIsInstance<SelectOption>()
				.filter { it.value == value }
				.firstOrNull()
			if (option != null) {
				return@e
			}

			// nope, lookup the option
			AppScope.launch {

				looker(value)
					.firstOrNull()
					?.let {
						select.add(it.toSelectOption(true))
					}
			}
		}
	}
}


private fun RemoteOption.toSelectOption(selected: Boolean): SelectOption =
	SelectOption(
		value,
		text,
		subtext,
		icon,
		divider,
		disabled,
		selected,
		className?.let { setOf(it) } ?: setOf()
	)
