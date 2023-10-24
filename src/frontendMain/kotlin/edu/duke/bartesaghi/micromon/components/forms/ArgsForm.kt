package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.batch
import edu.duke.bartesaghi.micromon.diagram.nodes.Node
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.pyp.toArgValues
import io.kvision.core.onEvent
import io.kvision.form.*
import io.kvision.form.check.CheckBox
import io.kvision.form.check.CheckBoxStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.panel.tabPanel
import io.kvision.table.Row
import io.kvision.table.cell
import io.kvision.table.row
import io.kvision.table.table


class ArgsForm(
	val args: Args,
	val outNodes: List<Node> = emptyList(),
	val enabled: Boolean = true,
	val blockId: String? = null
) : StringFormControl, Div(classes = setOf("args-form")) {

	companion object {
		private var counter = 0
	}

	private val idc = "args_form_${counter++}"
	override val flabel: FieldLabel = FieldLabel(idc, "", false)

	inner class Input : FormInput, Div() {

		val form: ArgsForm = this@ArgsForm

		override var disabled
			get() = false
			set(_) {}

		override var name: String? = null
		override var size: InputSize? = null
		override var validationStatus: ValidationStatus? = null

		private val values = ArgValues(form.args)
		var asToml: ArgValuesToml?
			get() = values.toToml()
			set(value) {

				val newValues = (value ?: "").toArgValues(form.args)

				// update the value store directly
				values.clear()
				values.setAll(newValues)

				// profiling shows these DOM updates are noticeably slow!
				// so batch updates to the DOM to fix it
				batch {

					// update the form controls
					for (arg in form.args.args) {
						val control = controls[arg]
							?: continue
						control.control.argValue = arg.type.valueOf(newValues[arg])
						control.updateIcon()
					}
				}
			}

		private val controls = Controls()
		private val advancedCheck = CheckBox(
			false,
			label = "Show advanced options"
		).apply {
			style = CheckBoxStyle.PRIMARY
			addCssClass("auto-scroll")
		}

		private val tabs = HashMap<String?,ArgsInputs>()

		override fun blur() {}
		override fun focus() {}

		init {

			// kotlin DSLs are dumb
			val me = this

			div(classes = setOf("options")) {
				add(me.advancedCheck)
			}

			if (form.args.groups.isNotEmpty()) {

				// filter out hidden tabs in the UI only, not the values
				val groups = form.args.groups
					.filter { form.blockId == null || it.hidden.isVisibleInBlock(form.blockId) }

				// show the arg in a tab panel, one tab for each group
				tabPanel {
					for (group in groups) {
						val tab = ArgsInputs(form.args.args(group), values, controls, form.outNodes, form.enabled)
						tabs[group.groupId] = tab
						addTab(group.name, tab)
					}
				}

			} else {

				// show all args at once
				val tab = ArgsInputs(form.args.args, values, controls, form.outNodes, form.enabled)
				tabs[null] = tab
				add(tab)
			}

			// wire up events
			advancedCheck.onEvent {
				change = {
					for (tab in tabs.values) {
						tab.showAdvanced(advancedCheck.value)
					}
				}
			}
		}
	}
	override val input = Input()

	override val invalidFeedback: InvalidFeedback get() = InvalidFeedback()

	override var value: ArgValuesToml?
		get() = input.asToml
		set(value) { input.asToml = value }

	override fun blur() = input.blur()
	override fun focus() = input.focus()

	override fun subscribe(observer: (String?) -> Unit): () -> Unit {
		throw Error("not implemented")
	}

	init {
		add(input)
	}
}


data class ControlInfo(
	val control: ArgInputControl,
	val icon: ArgIcon,
	val row: Row
) {

	fun updateIcon() {
		icon.state = if (control.isDefault()) {
			if (control.arg.required) {
				ArgIcon.State.Required
			} else {
				ArgIcon.State.Default
			}
		} else {
			ArgIcon.State.Specified
		}
	}
}


class Controls : Iterable<ControlInfo> {

	private val map = HashMap<String,ControlInfo>()

	operator fun set(arg: Arg, info: ControlInfo) {
		map[arg.fullId] = info
	}

	operator fun get(fullId: String): ControlInfo? =
		map[fullId]

	operator fun get(arg: Arg): ControlInfo? =
		get(arg.fullId)

	fun getOrThrow(fullId : String): ControlInfo =
		get(fullId)
			?: throw NoSuchElementException("no control for $fullId")

	fun getOrThrow(arg: Arg): ControlInfo =
		getOrThrow(arg.fullId)

	override fun iterator(): Iterator<ControlInfo> =
		map.values.iterator()
}


class ArgsInputs(
	val args: List<Arg>,
	val values: ArgValues,
	val controls: Controls,
	val outNodes: List<Node>,
	val enabled: Boolean
) : Div(classes = setOf("args-inputs")) {

	init {
		// layout the form with a table (*GASP* a table!! iOi)
		table {

			// create form inputs for all the args
			for (arg in args) {

				val icon = ArgIcon(ArgIcon.State.Default)

				val control: ArgInputControl = if (arg.input != null) {
					when (arg.input) {
						is ArgInput.ParFile -> ArgInputParFile(arg, outNodes)
						is ArgInput.TxtFile -> ArgInputTxtFile(arg, outNodes)
						is ArgInput.InitialModel -> ArgInputInitialModel(arg, outNodes)
						is ArgInput.HalfMap -> ArgInputHalfMap(arg, outNodes)
						is ArgInput.TrainedModel2D -> ArgInputTrainedModel2D(arg, outNodes)
						is ArgInput.TrainedModel3D -> ArgInputTrainedModel3D(arg, outNodes)
						is ArgInput.ClusterQueue -> ArgInputClusterQueue(arg)
					}
				} else {
					when (arg.type) {
						is ArgType.TBool -> ArgInputBool(arg)
						is ArgType.TInt -> ArgInputInt(arg)
						is ArgType.TFloat -> ArgInputFloat(arg)
						is ArgType.TFloat2 -> ArgInputFloat2(arg)
						is ArgType.TStr -> ArgInputStr(arg)
						is ArgType.TEnum -> ArgInputEnum(arg)
						is ArgType.TPath -> ArgInputPath(arg)
					}
				}

				// configure the control
				control.enabled = enabled

				// layout the controls on the form
				val row = row {

					if (arg.advanced) {
						addCssClass("advanced")
					}

					cell(classes = setOf("icon")) {
						add(icon)
					}
					cell(classes = setOf("label")) {
						fieldLabel(control.labelTarget ?: "(no label target)", arg.name)
					}
					cell(classes = setOf("control")) {
						add(control)
					}
					cell(classes = setOf("description")) {
						span(arg.description, classes = setOf("description"))
					}
				}

				// hide advanced options by default
				if (arg.advanced) {
					row.visible = false
				}

				val info = ControlInfo(control, icon, row)
				controls[arg] = info

				// wire up events
				control.onChange = {

					// if the current value is null, set another null to reload the default value
					if (control.argValue == null && arg.default != null) {
 						control.argValue = null
					}

					// keep the icon updated
					info.updateIcon()

					// update the outgoing values too,
					// but only save the value if it's different from the default
					values[arg] = control.argValue
						.takeIf { it != arg.default }

					// also update any downstream controls, if needed
					val destControl = control.destControl
					if (destControl != null) {

						// was the control at the default value before we changed this control?
						// we can't just check destControl.isDefault(), because this control's value has already changed
						// but we can check the icon to see if the control thinks it was at a default value
						if (controls.getOrThrow(destControl.arg).icon.state == ArgIcon.State.Default) {

							// yup, reset it to the default value
							destControl.argValue = null
						}
					}
				}
			}

			// resolve reference values and wire up source/dest controls
			for (info in controls) {
				val arg = info.control.arg
				if (arg.default is ArgValue.VRef) {
					val sourceControl = controls.getOrThrow(arg.default.srcFullId).control
					info.control.sourceControl = sourceControl
					sourceControl.destControl = info.control
				}
			}

			// set all controls to default values
			for (info in controls) {
				info.control.argValue = null
			}

			// init all icon states
			for (info in controls) {
				info.updateIcon()
			}
		}
	}

	fun showAdvanced(visible: Boolean) {
		args
			.filter { it.advanced }
			.mapNotNull { controls[it] }
			.forEach { it.row.visible = visible }
	}
}
