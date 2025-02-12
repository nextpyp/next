package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.diagram.nodes.Node
import edu.duke.bartesaghi.micromon.pyp.Arg
import edu.duke.bartesaghi.micromon.pyp.ArgValue
import edu.duke.bartesaghi.micromon.services.FileBrowserType
import io.kvision.core.onEvent


open class ArgInputFile(
	override val arg: Arg,
	filenameGlob: String? = null,
	initialFolder: String? = null
) : ArgInputControl, FilesystemPicker.Single.Control(
	FilesystemPicker.Single(
		target = FilesystemPicker.Target.Files,
		name = arg.fullId,
		initialFolder = initialFolder,
		filenameGlob = filenameGlob,
		globTypes = setOf(FileBrowserType.File,  FileBrowserType.Symlink)
	)
) {

	constructor(arg: Arg, filenameGlob: String, nodes: List<Node>, folderer: (Node) -> String?) : this(
		arg,
		filenameGlob,
		folderFromFirstNode(nodes, folderer)
	)


	companion object {

		fun folderFromFirstNode(nodes: List<Node>, folderer: (node: Node) -> String?): String? =
			nodes.firstOrNull()
				?.let(folderer)
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
