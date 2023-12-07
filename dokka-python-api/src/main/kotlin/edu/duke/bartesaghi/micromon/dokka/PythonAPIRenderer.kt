package edu.duke.bartesaghi.micromon.dokka

import org.jetbrains.dokka.pages.RootPageNode
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.renderers.Renderer
import kotlin.io.path.bufferedWriter


/**
 * Renders the collected model as a Python API
 */
class PythonAPIRenderer(val ctx: DokkaContext) : Renderer {

	override fun render(root: RootPageNode) {

		val model = (root as ModelCollector.Page).model

		val file = ctx.configuration.outputDir.resolve("gen.py").toPath()
		file.bufferedWriter().use { writer ->

			// TEMP
			writer.write("hello world")
			// TODO: NEXTTIME: render the python API
		}
	}
}
