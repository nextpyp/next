package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.Paths
import io.kvision.core.onEvent
import io.kvision.form.StringFormControl
import io.kvision.form.text.Text


class CreateFolderChooser(
	name: String? = null,
	value: String? = null
): BaseFormInput(name, classes = setOf("create-folder-chooser")) {

	private val parentPicker = FilesystemPicker.Single.Control(
		FilesystemPicker.Single(
			target = FilesystemPicker.Target.Folders,
		),
		label = "Parent"
	)

	private val nameText = Text(
		label = "Name"
	)

	private var path: String? = value

	init {

		// layout
		add(parentPicker)
		add(nameText)

		// wire up events
		parentPicker.onEvent {
			change = {
				if (!parsing) {
					assemble()
				}
			}
		}
		nameText.onEvent {
			input = {
				if (!parsing) {
					assemble()
				}
			}
		}

		// apply the intial value
		parse()
	}

	private var parsing = false

	private fun assemble() {

		path = null

		val parent = parentPicker.value
			?: return
		val name = nameText.value
			?.takeIf { it.isNotBlank() }
			?: return

		path = Paths.join(parent, name)
	}

	private fun parse() {
		parsing = true
		try {

			nameText.value = null
			parentPicker.value = null

			val path = path
				?: return
			val (parent, name) = Paths.pop(path)
			parent
				?: return

			parentPicker.value = parent
			nameText.value = name

		} finally {
			parsing = false
		}
	}

	var value: String?
		get() = path
		set(value) {
			path = value
			parse()
		}

	override var enabled: Boolean = true
		set(value) {
			field = value
			parentPicker.disabled = !value
			nameText.disabled = !value
		}

	override fun blur() = nameText.blur()
	override fun focus() = nameText.focus()


	class Control(override val input: CreateFolderChooser, label: String? = null) : BaseFormControl<CreateFolderChooser>(label), StringFormControl {

		override val labelReferent get() = input

		override var value: String?
			get() = input.value
			set(value) { input.value = value }

		// make no-op implementations for stuff we don't care about
		override fun subscribe(observer: (String?) -> Unit): () -> Unit = {}
	}

	fun control(label: String? = null) =
		Control(this, label)
}
