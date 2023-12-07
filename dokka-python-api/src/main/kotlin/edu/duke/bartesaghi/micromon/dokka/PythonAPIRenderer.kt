package edu.duke.bartesaghi.micromon.dokka

import org.jetbrains.dokka.pages.RootPageNode
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.renderers.Renderer
import java.io.Writer
import kotlin.io.path.bufferedWriter


/**
 * Renders the collected model as a Python API
 */
class PythonAPIRenderer(val ctx: DokkaContext) : Renderer {

	companion object {

		/** a python indentation */
		const val ind = "    "

		fun ind(times: Int): String =
			when (times) {
				0 -> ""
				else -> StringBuilder(ind.length*times).apply {
					for (i in 0 until times) {
						append(ind)
					}
				}.toString()
			}
	}

	override fun render(root: RootPageNode) {

		val model = (root as ModelCollector.Page).model

		val file = ctx.configuration.outputDir.resolve("gen.py").toPath()
		file.bufferedWriter().use { writer ->
			writer.write(model)
		}
	}

	private fun Writer.writeln(line: String? = null) {
		line?.let { write(it) }
		write("\n")
	}

	private fun Writer.write(model: Model) {

		writeln()

		// write imports
		writeln("from typing import Optional, List")
		writeln()
		writeln("from nextpyp.client import Client")

		// write the services
		for (service in model.services) {
			writeln()
			writeln()
			write(service)
		}

		// write the types
		for (type in model.types) {
			writeln()
			writeln()
			write(type)
		}
	}

	private fun Writer.write(service: Model.Service) {

		writeln("class ${service.name}Service:")
		writeln()
		writeln("${ind}def __init__(self, client: Client) -> None:")
		writeln("${ind}${ind}self.client = client")

		for (func in service.functions) {
			writeln()
			write(func)
		}
	}

	private fun Writer.write(func: Model.Service.Function) {

		val args = func.arguments
			.joinToString("") { arg ->
				val name = arg.name.caseCamelToSnake()
				val type = arg.type.render()
				", $name: $type"
			}
		val returns = func.returns?.render()
			?: "None"

		writeln("${ind}def ${func.name.caseCamelToSnake()}(self$args) -> $returns:")
		writeln("${ind}${ind}pass # TODO")
	}

	private fun Model.TypeRef.render(): String =
		when (packageName) {

			// convert basic types
			"kotlin" -> {
				when (name) {
					"String" -> "str"
					"Int", "Long" -> "int"
					else -> throw Error("Don't know how to handle kotlin type: $name")
				}
			}

			// convert collection types
			"kotlin.collections" -> {

				fun inner(i: Int): String =
					params.getOrNull(i)
						?.render()
						?: throw NoSuchElementException("$name type has no parameter at $i")

				when (name) {
					"List" -> "List[${inner(0)}]"
					"Set" -> "Set[${inner(0)}]"
					"Map" -> "Map[${inner(0)}, ${inner(1)}]"
					else -> throw Error("Don't know how to handle kotlin collection type: $name")
				}
			}

			// handle our types
			PACKAGE_SERVICES -> name

			else -> throw Error("Don't know how to handle type ref: $this")
		}
		// apply nullability
		.let {
			if (nullable) {
				"Optional[$it]"
			} else {
				it
			}
		}

	private fun Writer.write(type: Model.Type, indent: Int = 0) {

		val fstind = ind(indent)

		val args = type.props
			.joinToString("") {
				val name = it.name.caseCamelToSnake()
				val argType = it.type.render()
				", $name: $argType"
			}
0
		writeln("${fstind}class ${type.name}:")
		writeln()
		writeln("${fstind}${ind}def __init__(self$args) -> None:")
		if (type.props.isEmpty()) {
			throw Error("type ${type.id} has no properties")
		}
		for (prop in type.props) {
			val name = prop.name.caseCamelToSnake()
			writeln("${fstind}${ind}${ind}self.$name = $name")
		}

		// TODO: recurse
	}
}
