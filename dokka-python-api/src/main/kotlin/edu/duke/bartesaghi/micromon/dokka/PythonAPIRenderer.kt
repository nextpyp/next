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
			Indented(writer, 0).write(model)
		}
	}

	private fun Indented.write(model: Model) {

		writeln()

		// write imports
		writeln("from __future__ import annotations")
		writeln()
		writeln("from typing import Optional, List, Dict, Set, Any, Iterator, TYPE_CHECKING, Union, cast")
		writeln()
		writeln("import json")
		writeln("from websockets.legacy.client import WebSocketClientProtocol")
		writeln()
		writeln("from nextpyp.client.realtime import RealtimeService, RealtimeServiceConnection, RealtimeServiceUnexpectedMessage")
		writeln("if TYPE_CHECKING:")
		indent {
			writeln("from nextpyp.client import Client")
		}

		writeln()
		writeln()

		// write the services menu
		writeln("class Services:")
		writeln()
		indent {
			writeln("def __init__(self, client: Client) -> None:")
			indent {
				for (service in model.services) {
					val name = service.name.caseCamelToSnake()
					val type = "${service.name}Service"
					writeln("self.$name = $type(client)")
				}
			}
		}

		writeln()
		writeln()

		// write the services
		for (service in model.services) {
			writeln()
			writeln()
			write(service)
		}

		writeln()
		writeln()

		// write the realtime services
		writeln("class RealtimeServices:")
		writeln()
		indent {
			writeln("def __init__(self, client: Client) -> None:")
			indent {
				for (realtimeService in model.realtimeServices) {
					val name = realtimeService.name.caseCamelToSnake()
					val path = "'${realtimeService.path}'"
					writeln("self.$name = RealtimeService(client, $path, ${realtimeService.name}RealtimeServiceConnection)")
				}
			}
		}

		for (realtimeService in model.realtimeServices) {
			writeln()
			writeln()
			write(realtimeService)
		}

		// write the types
		for (type in model.types) {
			writeln()
			writeln()
			write(type)
		}
	}

	private fun Indented.write(service: Model.Service) {

		writeln("class ${service.name}Service:")
		writeln()
		indent {
			writeln("def __init__(self, client: Client) -> None:")
			indent {
				writeln("self.client = client")
			}
		}

		for (func in service.functions) {
			writeln()
			write(func)
		}
	}

	private fun Indented.write(func: Model.Service.Function) {

		val args = func.arguments
			.joinToString("") { arg ->
				val name = arg.name.caseCamelToSnake()
				val type = arg.type.render()
				", $name: $type"
			}
		val returns = func.returns?.render()
			?: "None"

		// TODO: document what permission is needed?

		indent {
			writeln("def ${func.name.caseCamelToSnake()}(self$args) -> $returns:")
			indent {
				writeln("path = '${func.path}'")
				val argNames = func.arguments
					.joinToString(", ") { it.name.caseCamelToSnake() }
				val call = "self.client._call(path, [$argNames])"
				when (val r = func.returns) {

					// no return value, just call the web service
					null -> writeln(call)

					// call the web service and then handle the response
					else -> {
						writeln("response = $call")
						writeln("if response is None:")
						indent {
							writeln("raise Exception(f'response expected from {path}, but none received')")
						}
						writeln("return ${r.renderReader("response")}")
					}
				}
			}
		}
	}

	private fun Indented.write(type: Model.Type) {
		if (type.enumValues != null) {
			writeEnum(type, type.enumValues)
		} else {
			writeClass(type)
		}
	}

	private fun Indented.writeClass(type: Model.Type) {

		val args = type.props
			.joinToString("") {
				val name = it.name.caseCamelToSnake()
				val argType = it.type.render()
				", $name: $argType"
			}

		// define the class
		writeln("class ${type.name}:")
		indent {

			writeln()

			writeln("TYPE_ID: str = '${type.packageName}.${type.names}'")

			writeln()

			// write the constructor
			if (type.props.isNotEmpty()) {
				writeln("def __init__(self$args) -> None:")
				indent {
					for (prop in type.props) {
						val name = prop.name.caseCamelToSnake()
						writeln("self.$name = $name")
					}
				}
			}

			writeln()

			// write the serializer
			writeln("def to_json(self) -> Dict[str,Any]:")
			indent {
				writeln("return {")
				indent {
					for (prop in type.props) {
						val name = prop.name.caseCamelToSnake()
						val value = prop.type.renderWriter("self.$name")
						writeln("'${prop.name}': $value,")
					}
				}
				writeln("}")
			}

			writeln()

			// write the deserializer
			writeln("@classmethod")
			writeln("def from_json(cls, json: Dict[str,Any]) -> ${type.names}:")
			indent {
				writeln("return cls(")
				indent {
					for (prop in type.props) {
						val json = "json['${prop.name}']"
						val name = prop.name.caseCamelToSnake()
						val value = prop.type.renderReader(json)
							.appendIf(prop.type.nullable) {
								" if '${prop.name}' in json and $json is not None else None"
							}
						writeln("$name=$value,")
					}
				}
				writeln(")")
			}

			writeln()

			// write __str__
			writeln("def __str__(self) -> str:")
			indent {
				writeln("props = ', '.join([")
				indent {
					for (prop in type.props) {
						val name = prop.name.caseCamelToSnake()
						writeln("f'$name={self.$name}',")
					}
				}
				writeln("])")
				writeln("return f'${type.name}[{props}]'")
			}

			writeln()

			// write __repr__
			writeln("def __repr__(self) -> str:")
			indent {
				writeln("return self.__str__()")
			}

			writeln()

			// impl __eq__
			// NOTE: __eq__ returns Any, not bool
			writeln("def __eq__(self, other: object) -> Any:")
			indent {
				writeln("if not isinstance(other, ${type.names}):")
				indent {
					writeln("return NotImplemented")
				}
				if (type.props.isNotEmpty()) {
					for ((i, prop) in type.props.withIndex()) {
						val name = prop.name.caseCamelToSnake()
						val comparison = "self.$name == other.$name"
						if (i == 0) {
							if (type.props.size == 1) {
								writeln("return $comparison")
							} else {
								writeln("return $comparison \\")
							}
						} else {
							indent {
								if (i == type.props.size - 1) {
									writeln("and $comparison")
								} else {
									writeln("and $comparison \\")
								}
							}
						}
					}
				} else {
					writeln("return True  # we are all the same! =D")
				}
			}

			// recurse
			for (inner in type.inners) {
				writeln()
				writeln()
				write(inner)
			}
		}
	}

	private fun Indented.writeEnum(type: Model.Type, values: List<String>) {

		// python won't allow things named None
		fun String.pysafe(): String =
			takeIf { it != "None" }
				?: "NoneVal"

		// define the class
		// NOTE: string-valued enums are only allowed in Python 3.11+
		//       so we can't use them here
		writeln("class ${type.name}:")
		indent {

			writeln()

			// write the enum values
			// NOTE: we'll assign them later to avoid circularity issues
			for (value in values) {
				writeln("${value.pysafe()}: ${type.name}")
			}

			writeln()

			// write the constructor
			writeln("def __init__(self, name: str) -> None:")
			indent {
				writeln("self.name = name")
			}

			writeln()

			// write the serializer
			writeln("def to_json(self) -> str:")
			indent {
				writeln("return self.name")
			}

			writeln()

			// write the deserializer
			writeln("@classmethod")
			writeln("def from_json(cls, name: str) -> ${type.name}:")
			indent {
				for ((i, value) in values.withIndex()) {
					if (i == 0) {
						writeln("if name == '$value':")
					} else {
						writeln("elif name == '$value':")
					}
					indent {
						writeln("return ${type.name}.${value.pysafe()}")
					}
				}
				writeln("else:")
				indent {
					writeln("return cls(name)")
				}
			}

			writeln()

			// write __str__
			writeln("def __str__(self) -> str:")
			indent {
				writeln("return self.name")
			}

			writeln()

			// write __repr__
			writeln("def __repr__(self) -> str:")
			indent {
				writeln("return self.__str__()")
			}

			writeln()

			// write the enum values functions
			writeln("def __len__(self) -> int:")
			indent {
				writeln("return ${values.size}")
			}

			writeln()

			writeln("def __iter__(self) -> Iterator[${type.name}]:")
			indent {
				val refs = values.joinToString(", ") {
					"${type.name}.${it.pysafe()}"
				}
				writeln("return [$refs].__iter__()")
			}

			writeln()

			// impl __eq__
			// NOTE: __eq__ returns Any, not bool
			writeln("def __eq__(self, other: object) -> Any:")
			indent {
				writeln("if not isinstance(other, ${type.name}):")
				indent {
					writeln("return NotImplemented")
				}
				writeln("return self.name == other.name")
			}

			writeln()

			// impl __hash__
			writeln("def __hash__(self) -> int:")
			indent {
				writeln("return hash(self.name)")
			}
		}

		writeln()

		// assign the enum values
		for (value in values) {
			writeln("${type.name}.${value.pysafe()} = ${type.name}('$value')")
		}
	}

	private fun Indented.write(realtimeService: Model.RealtimeService) {

		writeln("class ${realtimeService.name}RealtimeServiceConnection(RealtimeServiceConnection):")
		indent {

			writeln()

			writeln("def __init__(self, websocket: WebSocketClientProtocol) -> None:")
			indent {
				writeln("super(${realtimeService.name}RealtimeServiceConnection, self).__init__(websocket)")
			}

			// send functions
			for (msg in realtimeService.messagesC2S) {

				writeln()

				val name = msg.innerName.caseCamelToSnake()
				writeln("async def send_$name(self, msg: ${msg.name}) -> None:")
				indent {
					writeln("j = msg.to_json()")
					writeln("j['type'] = ${msg.name}.TYPE_ID")
					writeln("await self._send(j)")
				}
			}

			writeln()

			val names = realtimeService.messagesS2C
				.joinToString(", ") { it.name }
			writeln("async def recv(self) -> Union[$names]:")
			indent {
				writeln("msg = await self._recv()")
				writeln("type = msg.get('type')")
				writeln("match type:")
				indent {
					for (msg in realtimeService.messagesS2C) {
						writeln("case ${msg.name}.TYPE_ID:")
						indent {
							writeln("return ${msg.name}.from_json(msg)")
						}
					}
					writeln("case _:")
					indent {
						writeln("raise RealtimeServiceUnexpectedMessage(type)")
					}
				}
			}
		}
	}
}


private fun Writer.writeln(line: String? = null) {
	line?.let { write(it) }
	write("\n")
}


private class Indented(val writer: Writer, val numIndents: Int) {

	companion object {

		/** ewwww spaces, gross! */
		const val indent = "    "
	}

	fun Writer.writeIndents() {
		for (i in 0 until numIndents) {
			write(indent)
		}
	}

	fun writeln(line: String? = null) {
		writer.writeIndents()
		writer.writeln(line)
	}

	fun <R> indent(block: Indented.() -> R): R =
		Indented(writer, numIndents + 1).block()
}


private fun <R> Writer.indent(block: Indented.() -> R): R =
	Indented(this, 1).block()


private fun String.appendIf(cond: Boolean, getter: () -> String): String =
	if (cond) {
		this + getter()
	} else {
		this
	}



private fun Model.TypeRef.render(): String =
	when (packageName) {

		// convert basic types
		"kotlin" -> {
			when (name) {
				"String" -> "str"
				"Int", "Long" -> "int"
				"Boolean" -> "bool"
				"Float", "Double" -> "float"
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
				"Map" -> "Dict[${inner(0)}, ${inner(1)}]"
				else -> throw Error("Don't know how to handle kotlin collection type: $name")
			}
		}

		// handle our types
		in PACKAGES -> name

		// TODO: NEXTTIME:
		//  Don't know how to handle type ref: TypeRef(packageName=edu.duke.bartesaghi.micromon.pyp, name=Block, params=[])
		//  how to handle pyp things?
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

private fun Model.TypeRef.renderReader(expr: String): String =
	when (packageName) {

		// read basic types
		"kotlin" -> {
			when (name) {
				// no translation needed, just cast the type
				"String" -> "cast(str, $expr)"
				"Int", "Long" -> "cast(int, $expr)"
				"Boolean" -> "cast(bool, $expr)"
				"Float", "Double" -> "cast(float, $expr)"
				else -> throw Error("Don't know how to read kotlin type: $name")
			}
		}

		// convert collection types
		"kotlin.collections" -> {

			fun inner(i: Int): Model.TypeRef =
				params.getOrNull(i)
					?: throw NoSuchElementException("$name type has no parameter at $i")

			when (name) {
				"List" -> "[${inner(0).renderReader("x")} for x in $expr]"
				"Set" -> "{${inner(0).renderReader("x")} for x in $expr}"
				"Map" -> "{${inner(0).renderReader("k")}:${inner(1).renderReader("v")} for k,v in $expr}"
				else -> throw Error("Don't know how to read kotlin collection type: $name")
			}
		}

		// handle our types
		in PACKAGES -> "$name.from_json($expr)"

		else -> throw Error("Don't know how to read type: $this")
	}

private fun Model.TypeRef.renderWriter(varname: String): String =
	when (packageName) {

		// read basic types
		"kotlin" -> {
			when (name) {
				// no translation needed
				"String", "Int", "Long", "Boolean", "Float", "Double" -> varname
				else -> throw Error("Don't know how to read kotlin type: $name")
			}
		}

		// convert collection types
		"kotlin.collections" -> {

			fun inner(i: Int): Model.TypeRef =
				params.getOrNull(i)
					?: throw NoSuchElementException("$name type has no parameter at $i")

			when (name) {
				"List", "Set" -> "[${inner(0).renderWriter("x")} for x in $varname]"
				"Map" -> "[[${inner(0).renderWriter("k")},${inner(1).renderWriter("v")}] for k,v in $varname]"
				else -> throw Error("Don't know how to read kotlin collection type: $name")
			}
		}

		// handle our types
		in PACKAGES -> "$varname.to_json()"

		else -> throw Error("Don't know how to write type: $this")
	}
	// apply nullability
	.appendIf(nullable) {
		" if $varname is not None else None"
	}
