package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.IconStyled
import edu.duke.bartesaghi.micromon.services.JobArgs
import js.getHTMLElementOrThrow
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.dropdown.DropDown
import io.kvision.form.FormControl
import io.kvision.form.FormPanel
import io.kvision.form.check.CheckBox
import io.kvision.form.select.SelectInput
import io.kvision.form.text.TextInput
import io.kvision.html.Button
import io.kvision.html.Icon
import io.kvision.html.Link
import io.kvision.html.div
import io.kvision.modal.Modal
import js.getHTMLElement
import kotlinx.browser.document
import org.w3c.dom.*
import org.w3c.dom.events.MouseEvent


/** for the love of all that is holy, give me button control using positive terms instead of negative terms!! */
var Button.enabled: Boolean
	get() = !disabled
	set(value) { disabled = !value }

fun Button.enableClickIf(condition: Boolean, handler: Button.(MouseEvent) -> Unit): Button {
	enabled = condition
	if (condition) {
		onClick(handler)
	}
	return this
}


var DropDown.enabled: Boolean
	get() = !disabled
	set(value) { disabled = !value }


var CheckBox.enabled: Boolean
	get() = !disabled
	set(value) { disabled = !value }


var Link.enabled: Boolean
	get() = !hasCssClass("disabled")
	set(value) {
		if (value) {
			removeCssClass("disabled")
		} else {
			addCssClass("disabled")
		}
	}

fun Link.enableClickIf(condition: Boolean, handler: Link.(MouseEvent) -> Unit) {
	enabled = condition
	if (condition) {
		onClick(handler)
	}
}


/**
 * Shows icons in the form label
 */
var FormControl.icons: List<String>
	get() = flabel.getChildren()
		.filterIsInstance<Icon>()
		.map { it.icon }
	set(icons) {
		flabel.removeAll()
		for (icon in icons) {
			flabel.add(IconStyled(icon))
		}
	}


var FormControl.isChanged: Boolean
	get() = ChangedIcon in icons
	set(value) {
		if (value) {
			if (ChangedIcon !in icons) {
				icons += listOf(ChangedIcon)
			}
		} else {
			if (ChangedIcon in icons) {
				icons -= ChangedIcon
			}
		}
	}

private const val ChangedIcon = "fas fa-asterisk"


fun <T:Any> T.trackChanges(form: FormPanel<T>, mapper: ArgsMapper<T>) {

	val args = mapper.toForm(this)

	for ((key, control) in form.form.fields) {

		// save the key in the control name so we can find it later without the form
		control.name = key

		// initially populate the changed icons
		control.updateChanged(args)

		// hook events to keep the icons up to date
		(control.input as Widget).onEvent {
			change = { control.updateChanged(args) }
		}
	}
}

val FormControl.key: String
	get() = name ?: throw IllegalStateException("form control \"${flabel.content}\" has not been initialized to track changes")

fun <T:Any> FormControl.updateChanged(args: T) {
	// `T` are data classes where the property names are the form keys,
	// so the arg values can be accessed in the plain js object by the form key
	isChanged = getValue() != args.asDynamic()[key]
}


/**
 * Calls the button's onclick event handlers
 */
fun Button.click() {
	val elem = getHTMLElementOrThrow() as HTMLButtonElement
	elem.click()
}


/**
 * Captures the browser's form submit event and re-routes it to the given button
 */
fun <T:Any> FormPanel<T>.setSubmitButton(button: Button) {
	onEvent {
		submit = {

			// don't run the browser's submit handler
			it.preventDefault()

			button.click()
		}
	}
}


/**
 * sets the form data, but also updates the form change indicators,
 * since setting form values programatically (rather than by user actions) doesn't trigger change events
 */
fun <T:Any> FormPanel<T>.setDataUpdateChanged(args: T) {
	setData(args)
	for (control in form.fields.values) {
		control.updateChanged(args)
	}
}


/**
 * Sometimes the arguments need to be mapped to something else
 * to workaround limitations in the UI.
 */
class ArgsMapper<T:Any>(
	val toForm: (T) -> T,
	val fromForm: (T) -> T
) {
	companion object {

		/** returns a mapper that makes no changes */
		fun <T:Any> nop(): ArgsMapper<T> =
			ArgsMapper(
				toForm = { it },
				fromForm = { it }
			)
	}
}


fun <T:Any> Modal.addSaveButton(
	form: FormPanel<T>,
	mapper: ArgsMapper<T>,
	onDone: (T) -> Unit
) {
	addButton(saveButton(form, mapper) {
		hide()
		onDone(it)
	})
}

fun <T:Any> Modal.addSaveButton(
	form: FormPanel<T>,
	onDone: (T) -> Unit
) =
	addSaveButton(form, ArgsMapper.nop(), onDone)

fun <T:Any> Modal.addSaveResetButtons(
	form: FormPanel<T>,
	args: JobArgs<T>?,
	mapper: ArgsMapper<T>,
	onDone: (T) -> Unit
) {
	addButton(resetButton(form, args, mapper))
	addButton(saveButton(form, mapper) {
		hide()
		onDone(it)
	})
}

fun <T:Any> Modal.addSaveResetButtons(
	form: FormPanel<T>,
	args: JobArgs<T>?,
	onDone: (T) -> Unit
) =
	addSaveResetButtons(form, args, ArgsMapper.nop(), onDone)

fun <T:Any> FormPanel<T>.addSaveResetButtons(
	args: JobArgs<T>?,
	mapper: ArgsMapper<T>,
	onDone: (T) -> Unit
) {
	val form = this
	div(classes = setOf("buttons")) {
		add(resetButton(form, args, mapper))
		add(saveButton(form, mapper, onDone))
	}
}

fun <T:Any> FormPanel<T>.addSaveResetButtons(
	args: JobArgs<T>?,
	onDone: (T) -> Unit
) =
	addSaveResetButtons(args, ArgsMapper.nop(), onDone)


/**
 * init the form with the newest args, if needed
 */
fun <T:Any> FormPanel<T>.init(
	args: JobArgs<T>?,
	mapper: ArgsMapper<T> = ArgsMapper.nop()
) {

	// init the form with the newest args, if needed
	val newestArgs = args?.newest()?.args
	if (newestArgs != null) {
		form.setData(mapper.toForm(newestArgs))
	}

	// track changes against the finished args, if any
	args?.finished?.trackChanges(this, mapper)
}

/**
 * a button to reset the form to the finished state, if needed
 */
private fun <T:Any> resetButton(
	form: FormPanel<T>,
	args: JobArgs<T>?,
	mapper: ArgsMapper<T>
): Button =
	Button("Reset", classes = setOf("reset-button")).apply {
		title = "Set the form to match the values used at the last run of this job, erasing any current changes."
		val finishedArgs = args?.finished
		if (finishedArgs != null) {
			onClick {
				form.setDataUpdateChanged(mapper.toForm(finishedArgs))
			}
		} else {
			enabled = false
			title += " (Disabled since there are no previous values to restore)"
		}
	}

/**
 * A button to validate the form and pass the results onto a save function
 */
private fun <T:Any> saveButton(
	form: FormPanel<T>,
	mapper: ArgsMapper<T>,
	onDone: (T) -> Unit
): Button =
	Button("Save").onClick {
		disabled = true
		try {
			if (form.validate()) {
				onDone(mapper.fromForm(form.getData()))
			}
		} finally {
			disabled = false
		}
	}


/**
 * KVision doesn't make it easy to find the options currently loaded by a SelectRemote,
 * so look at the raw DOM to find the option elements
 */
fun SelectInput.findOptions(): List<HTMLOptionElement> =
	(getHTMLElement() as? HTMLSelectElement?)
		?.options
		?.asList()
		?.map { it as HTMLOptionElement }
		?: emptyList()

/**
 * get the raw DOM element corresponding to the selected value, if any
 */
val SelectInput.valueOption: HTMLOptionElement? get() =
	findOptions()
		.find { it.value == this.value }


fun TextInput.selectAll(): Boolean {

	val elem = getHTMLElement() as? HTMLInputElement
		?: return false

	elem.focus()
	elem.setSelectionRange(0, elem.value.length)
	return true
}

fun TextInput.copyToClipboard(): Boolean {
	if (!selectAll()) {
		return false
	}
	document.execCommand("copy")
	return true
}
