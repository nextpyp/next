package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.pyp.Arg
import edu.duke.bartesaghi.micromon.pyp.ArgType
import edu.duke.bartesaghi.micromon.pyp.ArgValue
import io.kvision.core.onEvent


class ArgInputPath(
	override val arg: Arg
) : ArgInputControl, FilesystemPicker(
	target = when ((arg.type as ArgType.TPath).kind) {
		ArgType.TPath.Kind.Files -> when (arg.type.glob) {
			true -> Target.FilesGlob
			false -> Target.Files
		}
		ArgType.TPath.Kind.Folders -> when (arg.type.glob) {
			true -> Target.FoldersGlob
			false -> Target.Folders
		}
	},
	multiple = false,
	name = arg.fullId
	// TODO: use job inputs to show shortcuts to the user?
) {

	val default: String? get() = when (arg.default) {
		is ArgValue.VRef -> (sourceControlOrThrow as ArgInputPath).getValue().firstOrNull()
		is ArgValue.VPath -> arg.default.value
		else -> null
	}

	override var argValue: ArgValue?
		get() =
			// convert from list of strings (maybe empty) to string (maybe null)
			getValue().firstOrNull()?.let { ArgValue.VPath(it) }
		set(v) {
			// convert from string (maybe null) to list of strings (maybe empty)
			val path = (v as? ArgValue.VPath)?.value ?: default
			if (path != null) {
				setValue(listOf(path))
			} else {
				setValue(emptyList<String>())
			}
		}

	override fun isDefault(): Boolean =
		getValue().firstOrNull() == default

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
