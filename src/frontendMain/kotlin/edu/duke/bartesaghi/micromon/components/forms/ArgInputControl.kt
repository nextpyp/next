package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.pyp.Arg
import edu.duke.bartesaghi.micromon.pyp.ArgValue
import io.kvision.core.Widget
import io.kvision.form.FormControl


interface ArgInputControl : FormControl, EnableableControl {

	val arg: Arg

	var argValue: ArgValue?
	fun isDefault(): Boolean
	var sourceControl: ArgInputControl?
	var destControl : ArgInputControl?
	var onChange: (() -> Unit)?

	val sourceControlOrThrow get() =
		sourceControl
			?: throw NoSuchElementException("no source control linked")

	val labelTarget: String? get() = (input as Widget).id
}
