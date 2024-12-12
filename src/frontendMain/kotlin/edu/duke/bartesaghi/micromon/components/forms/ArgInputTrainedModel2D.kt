package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.diagram.nodes.*
import edu.duke.bartesaghi.micromon.pyp.Arg
import edu.duke.bartesaghi.micromon.pyp.ArgValue
import edu.duke.bartesaghi.micromon.services.FileBrowserType
import edu.duke.bartesaghi.micromon.services.PathType
import io.kvision.core.onEvent


class ArgInputTrainedModel2D(
	override val arg: Arg,
	outNodes: List<Node>
) : ArgInputControl, FilesystemPicker.Single(
	target = FilesystemPicker.Target.Files,
	name = arg.fullId,
	initialFolder = initialFolder(outNodes),
	filenameGlob = "*.training",
	globTypes = setOf(FileBrowserType.File,  FileBrowserType.Symlink)
) {

	companion object {

		fun initialFolder(outNodes: List<Node>): String? =
			outNodes.firstOrNull()
				?.let { PathType.Project.make(it.projectFolder) }
	}

	val default: String? get() = null

	override var argValue: ArgValue?
		get() =
			getValue()?.let { ArgValue.VPath(it) }
		set(v) {
			setValue((v as? ArgValue.VPath)?.value ?: default)
		}

	override fun isDefault(): Boolean =
		getValue() == default

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
