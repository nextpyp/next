package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.services.FileBrowserType
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.span
import io.kvision.utils.obj
import org.w3c.dom.CustomEventInit


open class FilesystemPicker(
	val target: Target,
	val multiple: Boolean,
	defaultPaths: List<String> = emptyList(),
	name: String? = null,
	label: String? = null,
	initialFolder: String? = null,
	/** applies a filter to filenames, only used for target=Files, ignored otherwise */
	filenameGlob: String? = null,
	/** controls which file browser types the glob will be applied to */
	globTypes: Set<FileBrowserType> = FileBrowser.PickFile.DEFAULT_GLOB_TYPES
) : BaseFormControl(name, label, classes = setOf("filesystem-picker")) {

	enum class Target {
		Files,
		Folders,
		FilesGlob,
		FoldersGlob
	}

	var paths: List<String> = defaultPaths
		set(value) {

			// exit early if there's no actual change
			if (field == value) {
				return
			}

			// apply the change
			field = value
			update()

			// notify listeners
			dispatchEvent("change", obj<CustomEventInit> { detail = value })
		}

	private val chooseButton =
		Button(
			text = "",
			icon = "fas fa-search",
			disabled = input.disabled
		).apply {
			title = "Choose a new value"
		}
		.onClick {

			// map the picker target to a file browser task
			when (target) {

				Target.Files -> {
					val task = FileBrowser.PickFile(paths.firstOrNull(), filenameGlob, globTypes)
					FileBrowser(task).launch(initialFolder) {
						paths = task.file
							?.let { listOf(it) }
							?: emptyList()
					}
				}

				Target.Folders -> {
					val task = FileBrowser.PickFolder(paths.firstOrNull())
					FileBrowser(task).launch(initialFolder) {
						paths = task.folder
							?.let { listOf(it) }
							?: emptyList()
					}
				}

				Target.FilesGlob -> {
					val task = FileBrowser.PickFileGlob(paths.firstOrNull())
					FileBrowser(task).launch(initialFolder) {
						paths = task.glob
							?.let { listOf(it) }
							?: emptyList()
					}
				}

				Target.FoldersGlob -> {
					val task = FileBrowser.PickFolderGlob(paths.firstOrNull())
					FileBrowser(task).launch(initialFolder) {
						paths = task.glob
							?.let { listOf(it) }
							?: emptyList()
					}
				}
			}
		}

	private val removeButton = Button(
		text = "",
		icon = "fas fa-trash",
		disabled = true
	).apply {
		title = "Remove the selected value"
	}.onClick {
		paths = emptyList()
	}

	private fun update() {

		val picker = this

		removeAll()

		// draw the controls
		add(removeButton)
		add(chooseButton)
		span {

			fun String.filename(): String =
				split('/').last()

			fun String.prefixFilename(): Pair<String,String> =
				split('/').let {
					when (it.size) {
						0 -> "" to ""
						1 -> "" to it[0]
						else -> it.subList(0, it.size - 1).joinToString("/") to "/" + it.last()
					}
				}

			if (picker.paths.isNotEmpty()) {
				if (!picker.disabled) {
					picker.removeButton.enabled = true
				}
				when (picker.target) {

					Target.Files -> {
						span(classes = setOf("path-prefix")) {
							content = picker.paths.joinToString(", ") { it.filename() }
						}
					}

					Target.Folders -> {
						span(classes = setOf("path-prefix")) {
							content = picker.paths.joinToString(", ")
						}
					}

					Target.FilesGlob -> {
						picker.paths.firstOrNull()?.let {
							val (prefix, filename) = it.prefixFilename()
							span(prefix, classes = setOf("path-prefix"))
							span(filename)
						}
					}

					Target.FoldersGlob -> {
						picker.paths.firstOrNull()?.let {
							val (prefix, filename) = it.prefixFilename()
							span(prefix, classes = setOf("path-prefix"))
							span(filename)
						}
					}
				}
			} else {
				if (!picker.disabled) {
					picker.removeButton.enabled = false
				}
				content = "(none)"
				addCssClass("empty")
			}
		}
	}

	init {
		update()
	}

	// route events and state to the button
	override var disabled: Boolean = true
		set(value) {
			field = value
			removeButton.disabled = value
			chooseButton.disabled = value
		}
	override fun blur() {
		removeButton.blur()
		chooseButton.blur()
	}
	override fun focus() = chooseButton.focus()


	override fun getValue(): List<String> =
		paths

	/** NOTE: this toString() transformation is not generally reversible, since linux paths can contain just about any character */
	override fun getValueAsString(): String =
		paths.joinToString(" ")

	override fun setValue(v: Any?) {
		paths = when (v) {
			is List<*> -> {
				v.map { it as String }
			}
			else -> emptyList()
		}
	}


	/**
	 * A wrapper around FilesystemPicker that whose value functions use String instead of List<String>
	 */
	open class Single(
		target: Target,
		name: String? = null,
		label: String? = null,
		initialFolder: String? = null,
		filenameGlob: String? = null,
		globTypes: Set<FileBrowserType> = FileBrowser.PickFile.DEFAULT_GLOB_TYPES
	) : BaseFormControl(name, label, classes = setOf("filesystem-picker")) {

		val picker = FilesystemPicker(
			target,
			multiple = false,
			name = name,
			label = label,
			initialFolder = initialFolder,
			filenameGlob = filenameGlob,
			globTypes = globTypes
		)

		init {
			add(picker)

			// forward change events from the inner picker control to the outer control
			picker.onEvent {
				change = { event ->
					this@Single.getSnOn()?.run {
						change?.invoke(event)
					}
				}
			}
		}

		override var disabled
			get() = picker.disabled
			set(value) { picker.disabled = value }

		override fun blur() = picker.blur()
		override fun focus() = picker.focus()

		override fun getValue(): String? =
			picker.getValue().firstOrNull()

		override fun getValueAsString(): String? =
			getValue()

		override fun setValue(v: Any?) {
			when (v) {
				is String -> {
					picker.setValue(listOf(v))
				}
				else -> Unit
			}
		}
	}
}
