package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.diagram.nodes.*
import edu.duke.bartesaghi.micromon.pyp.Arg
import edu.duke.bartesaghi.micromon.pyp.ArgValue
import edu.duke.bartesaghi.micromon.services.FileBrowserType
import edu.duke.bartesaghi.micromon.services.PathType
import io.kvision.core.onEvent


class ArgInputStarFile(
	override val arg: Arg,
	outNodes: List<Node>
) : ArgInputControl, FilesystemPicker.Single(
	target = FilesystemPicker.Target.Files,
	name = arg.fullId,
	initialFolder = initialFolder(outNodes),
	filenameGlob = "*.star",
	globTypes = setOf(FileBrowserType.File,  FileBrowserType.Symlink)
) {

	companion object {

		fun initialFolder(outNodes: List<Node>): String? =
			when (val node = outNodes.firstOrNull()) {

				is SingleParticlePreprocessingNode,
				is TomographyPreprocessingNode,
				is TomographyPickingNode,
				// TODO: segmented picking blocks, when they're ready
				is TomographyParticlesEvalNode,
				is SingleParticleCoarseRefinementNode,
				is SingleParticleFineRefinementNode,
				is SingleParticleFlexibleRefinementNode,
				is SingleParticlePostprocessingNode,
				is SingleParticleMaskingNode,
				is TomographyCoarseRefinementNode,
				is TomographyFineRefinementNode,
				is TomographyMovieCleaningNode,
				is TomographyFlexibleRefinementNode -> PathType.Project.make("${node.dir}/relion")

				else -> null
			}
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
