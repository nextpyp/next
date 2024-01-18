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
		indent {

			docstring("""
				|This class houses all of the different regular services that can be accessed by the NextPYP client,
				|with each service exposed as a class attribute.
				|
				|Each regular service contains a group of functions that work in your app just like regular functions,
				|except the function is executed on the NextPYP website instead of in your app.
				|Calling a service function will cause your app to send a request to the NextPYP website
				|and then wait until the response arrives.
				|
				|Regular services are different from realtime services. To learn more about realtime services,
				|see :py:class:`.RealtimeServices`.
				|
				|Examples
				|--------
				|
				|Call the ``list`` functon of the ``projects`` service:
				|
				|.. code-block:: python
				|
				|	projects = client.services.projects.list('user_id')
			""")

			writeln()

			writeln("def __init__(self, client: Client) -> None:")
			indent {
				for (service in model.services) {
					val name = service.name.caseCamelToSnake()
					val type = "${service.name}Service"
					writeln("self.$name: $type = $type(client)")
					writeln("\"\"\"")
					writeln("The ${service.name} service.")
					writeln("See: :py:class:`.$type`")
					writeln("\"\"\"")
					writeln()
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
		indent {

			docstring("""
				|All of the different realtime services that can be accessed by the NextPYP client
				|
				|Realtime services exchange messages with the NextPYP website, rather than perform function calls,
				|like regular services. The message exchange format lets the client send a message to the website
				|and then wait for any number of different responses which can all arrive different times.
				|In effect, this kind of connection lets the client stream data back from the website as soon as it's ready.
				|
				|Realtime services use Python's asyncio system over a HTTP WebSocket connection to send and receive
				|messages to/from the website. This technique results in a lower event latency
				|than more traditional connection polling techniques, and can be more efficient with network resources as well.
				|
				|Realtime services are different from regular services. To learn more about regular services,
				|see :py:class:`.Services`.
				|
				|Examples
				|--------
				|
				|Call a realtime service:
				|
				|.. code-block:: python
				|
				|	async with client.realtime_services.project as project:
				|	
				|		await project.send_listen_to_project(RealTimeC2SListenToProject(
				|			user_id='user_id',
				|			project_id='project_id'
				|		))
				|		
				|		msg = await project.recv()
				|		if type(msg) is RealTimeS2CProjectStatus:
				|			print(f'project status: {msg}')
				|		else:
				|			print(f'received other msg: {msg}')
				|
			""")

			writeln()

			writeln("def __init__(self, client: Client) -> None:")
			indent {
				for (realtimeService in model.realtimeServices) {

					val name = realtimeService.name.caseCamelToSnake()
					val path = "'${realtimeService.path}'"
					val connectionClass = "${realtimeService.name}RealtimeServiceConnection"

					writeln("self.$name: RealtimeService[$connectionClass] = RealtimeService(client, $path, ${realtimeService.name}RealtimeServiceConnection)")

					docstring("The ${realtimeService.name} realtime service, see :py:class:`.$connectionClass`")
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
		indent {

			// copy the interface kdocs, if any, into the Python docstring
			// NOTE: Sphinx seems to do fine here without any docstrings, so we don't need default content here
			docstring(service.doc.textOrUndocumented)

			writeln()

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

		indent {
			writeln("def ${func.name.caseCamelToSnake()}(self$args) -> $returns:")
			indent {

				// copy the function kdocs, if any, into the Python docstring
				// but make sure something gets written, or Sphinx will do weird stuff
				docstring("""
					|${func.doc.textOrUndocumented}
					|
					|:Permission Needed: ${func.appPermissionId?.let { "``$it``"} ?: "none"}
				""".trimMargin())

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
		writeln("class ${type.flatName}:")
		indent {

			// copy the class kdocs, if any, into the Python docstring
			docstring(type.doc.textOrUndocumented)

			writeln()

			writeln("TYPE_ID: str = '${type.packageName}.${type.names}'")

			writeln()

			// write the constructor, if there's anything to construct
			if (type.props.isNotEmpty()) {
				writeln("def __init__(self$args) -> None:")
				indent {
					for (prop in type.props) {
						val name = prop.name.caseCamelToSnake()
						writeln("self.$name: ${prop.type.render()} = $name")
						docstring(prop.doc.textOrUndocumented)
					}
				}
			}

			writeln()

			// write the serializer
			writeln("def to_json(self) -> Dict[str,Any]:")
			indent {

				docstring("Converts this class instance into JSON")

				writeln()

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
			writeln("def from_json(cls, json: Dict[str,Any]) -> ${type.flatName}:")
			indent {

				docstring("Creates a new class instance from JSON")

				writeln()

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
				writeln("return f'${type.flatName}[{props}]'")
			}

			writeln()

			// write __repr__
			writeln("def __repr__(self) -> str:")
			indent {
				writeln("return self.__str__()")
			}

			writeln()

			// implement equality comparisons
			// NOTE: __eq__ returns Any, not bool
			writeln("def __eq__(self, other: object) -> Any:")
			indent {
				writeln("if not isinstance(other, ${type.flatName}):")
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
		}

		// recurse, but write inner classes as top-level classes
		// NOTE: while Python itself technically supports nested classes,
		//       basically nothing else does (like freaking Sphinx!), so don't use them
		for (inner in type.inners) {
			writeln()
			writeln()
			write(inner)
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
		writeln("class ${type.flatName}:")
		indent {

			// copy the enum kdocs, if any, into the Python docstring
			docstring(type.doc.textOrUndocumented)

			writeln()

			// write the enum values
			// NOTE: we'll assign them later to avoid circularity issues
			for (value in values) {
				writeln("${value.pysafe()}: ${type.flatName}")
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

				docstring("Converts this enum value into JSON")

				writeln()

				writeln("return self.name")
			}

			writeln()

			// write the deserializer
			writeln("@classmethod")
			writeln("def from_json(cls, name: str) -> ${type.flatName}:")
			indent {

				docstring("Reads this enum value from JSON")

				writeln()

				for ((i, value) in values.withIndex()) {
					if (i == 0) {
						writeln("if name == '$value':")
					} else {
						writeln("elif name == '$value':")
					}
					indent {
						writeln("return ${type.flatName}.${value.pysafe()}")
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

			writeln("def __iter__(self) -> Iterator[${type.flatName}]:")
			indent {
				val refs = values.joinToString(", ") {
					"${type.flatName}.${it.pysafe()}"
				}
				writeln("return [$refs].__iter__()")
			}

			writeln()

			// implement equality comparisons
			// NOTE: __eq__ returns Any, not bool
			writeln("def __eq__(self, other: object) -> Any:")
			indent {
				writeln("if not isinstance(other, ${type.flatName}):")
				indent {
					writeln("return NotImplemented")
				}
				writeln("return self.name == other.name")
			}

			writeln()

			// implement hashing
			writeln("def __hash__(self) -> int:")
			indent {
				writeln("return hash(self.name)")
			}
		}

		writeln()

		// assign the enum values
		for (value in values) {
			writeln("${type.flatName}.${value.pysafe()} = ${type.flatName}('$value')")
		}
	}

	private fun Indented.write(realtimeService: Model.RealtimeService) {

		writeln("class ${realtimeService.name}RealtimeServiceConnection(RealtimeServiceConnection):")
		indent {

			// copy the class kdocs, if any, into the Python docstring
			docstring(run {

				// write the message types into the docstring as well
				val c2s = realtimeService.messagesC2S
					.map { "* :py:class:`.${it.flatName}`" }
				val s2c = realtimeService.messagesS2C
					.map { "* :py:class:`.${it.flatName}`" }

				"""
					|${realtimeService.doc.textOrUndocumented}
					|
					|:Permission Needed: ${realtimeService.appPermissionId?.let { "``$it``"} ?: "none"}
					|
					|:Client to Server messages:
					|
					|${c2s.joinToString("\n|")}
					|
					|:Server to Client messages:
					|
					|${s2c.joinToString("\n|")}
				""".trimMargin()
			})

			writeln()

			writeln("def __init__(self, websocket: WebSocketClientProtocol) -> None:")
			indent {
				writeln("super(${realtimeService.name}RealtimeServiceConnection, self).__init__(websocket)")
			}

			// send functions
			for (msg in realtimeService.messagesC2S) {

				writeln()

				val name = msg.innerName.caseCamelToSnake()
				writeln("async def send_$name(self, msg: ${msg.flatName}) -> None:")
				indent {

					docstring("Sends a ${msg.flatName} message")

					writeln()

					writeln("j = msg.to_json()")
					writeln("j['type'] = ${msg.flatName}.TYPE_ID")
					writeln("await self._send(j)")
				}
			}

			writeln()

			val names = realtimeService.messagesS2C
				.joinToString(", ") { it.flatName }
			writeln("async def recv(self) -> Union[$names]:")
			indent {
				docstring(run {

					val cases = realtimeService.messagesS2C
						.withIndex()
						.joinToString("\n|") { (i, msg) ->
							val cond = if (i == 0) {
								"if"
							} else {
								"elif"
							}
							"""
								|	$cond type(msg) is ${msg.flatName}:
								|		print(f'received message: {msg}')
							""".trimMargin()
						}

					"""
					|Receives a message from the website. Could be any one of several different messages.
					|
					|Examples
					|--------
					|
					|Receive a message from this realtime service:
					|
					|.. code-block:: python
					|
					|	msg = await project.recv()
					|$cases
					|	else:
					|		raise Exception(f'received unexpected msg: {msg}')
					"""
				})

				writeln()

				writeln("msg = await self._recv()")
				writeln("type = msg.get('type')")
				writeln("match type:")
				indent {
					for (msg in realtimeService.messagesS2C) {
						writeln("case ${msg.flatName}.TYPE_ID:")
						indent {
							writeln("return ${msg.flatName}.from_json(msg)")
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
		const val INDENT = "    "
	}

	fun Writer.writeIndents() {
		for (i in 0 until numIndents) {
			write(INDENT)
		}
	}

	fun writeln(line: String? = null) {
		writer.writeIndents()
		writer.writeln(line)
	}

	fun writeMultiline(text: String) {
		text.trimMargin()
			.lineSequence()
			.forEach { writeln(it) }
	}

	fun docstring(text: String) {
		writeln("\"\"\"")
		// NOTE: replace tabs with four spaces,
		// because otherwise the RST processor will expand tabs to eight spaces,
		// which just looks dumb
		writeMultiline(text.replace("\t", "    "))
		writeln("\"\"\"")
	}

	fun <R> indent(block: Indented.() -> R): R =
		Indented(writer, numIndents + 1).block()
}


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
		in PACKAGES -> flatName

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
		in PACKAGES -> "$flatName.from_json($expr)"

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
