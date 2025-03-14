package edu.duke.bartesaghi.micromon.dokka

import org.jetbrains.dokka.model.*
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

		println("Write Generated API to: $file")
	}

	private fun Indented.write(model: Model) {

		writeln()

		// write imports
		writeln("from __future__ import annotations")
		writeln()
		writeln("from typing import Optional, List, Dict, Set, Any, Iterator, TYPE_CHECKING, Union, cast, TypeVar, Generic, Callable")
		writeln("from abc import ABCMeta, abstractmethod")
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

		// write constants
		writeln("API_VERSION = '${model.apiVersion}'")

		writeln()
		writeln()

		// write type parameters
		writeln("T = TypeVar('T')") // used in helper functions
		writeln("R = TypeVar('R')") // used in helper functions
		for (param in model.typeParameterNames) {
			if (param in listOf("T", "R")) {
				continue // already have it
			}
			writeln("$param = TypeVar('$param')")
		}

		// write type aliases
		for (typeAlias in model.typeAliases.sortedBy { it.id }) {

			writeln()
			writeln()

			// HACKHACK: type aliases don't translate well to Python-land,
			//           so handle them on a case-by-case basis
			if (typeAlias.isOption) {
				writeln("${typeAlias.name} = list")
			} else if (typeAlias.isToString) {
				when (typeAlias.typeParams.size) {
					0 -> writeln("${typeAlias.name} = str")
					1 -> {
						val param = typeAlias.typeParams[0]
						for (instance in param.instances) {
							writeln("${typeAlias.render(model, listOf(instance))} = str")
						}
					}
					else -> throw Error("don't yet know how to handle type alias to string with multiple parameters")
				}
			} else {
				throw Error("don't yet know how to handle type alias: $typeAlias")
			}
		}

		writeln()
		writeln()

		// write helper functions
		writeln("def optional_map(val: Optional[T], func: Callable[[T], R]) -> Optional[R]:")
		indent {
			docstring("""
				|map the optional type,
				|so we can avoid using intermediate variables and simplify the code generator
			""".trimMargin())
			writeln("if val is None:")
			indent {
				writeln("return None")
			}
			writeln("else:")
			indent {
				writeln("return func(val)")
			}
		}

		writeln()
		writeln()

		writeln("def none_map(val: Optional[T], func: Callable[[], T]) -> T:")
		indent {
			docstring("""
				|map the None value in the optional type,
				|so we can avoid using intermediate variables and simplify the code generator
			""".trimMargin())
			writeln("if val is None:")
			indent {
				writeln("return func()")
			}
			writeln("else:")
			indent {
				writeln("return val")
			}
		}

		writeln()
		writeln()

		writeln("def none_raise(val: Optional[T], func: Callable[[], Exception]) -> T:")
		indent {
			docstring("""
				|if the optional type is None, raise the exception created by the lambda
				|(because apparently Python doesn't allow lambdas consisting only of raise statements),
				|so we can avoid using intermediate variables and simplify the code generator
			""".trimMargin())
			writeln("if val is None:")
			indent {
				writeln("raise func()")
			}
			writeln("else:")
			indent {
				writeln("return val")
			}
		}

		writeln()
		writeln()

		writeln("def option_unwrap(val: Any) -> Optional[Any]:")
		indent {
			writeln("if len(val) == 1:")
			indent {
				writeln("return val[0]")
			}
			writeln("else:")
			indent {
				writeln("return None")
			}
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
			write(service, model)
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

		// write the exernal types
		for (type in model.externalTypes) {
			writeln()
			writeln()
			write(type, model)
		}

		// write the types
		for (type in model.types) {
			writeln()
			writeln()
			write(type, model)
		}
	}

	private fun Indented.write(service: Model.Service, model: Model) {

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
			write(func, model)
		}
	}

	private fun Indented.write(func: Model.Service.Function, model: Model) {

		val args = func.arguments
			.joinToString("") { arg ->
				val name = arg.name.caseCamelToSnake()
				val type = arg.type.render(model)
				", $name: $type"
			}
		val returns = func.returns?.render(model)
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
				val argValues = func.arguments
					.joinToString(", ") { arg ->
						arg.type.renderWriter(arg.name.caseCamelToSnake(), model, emptyList())
					}
				val call = "self.client._transport.call(path, [$argValues])"
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
						writeln("return ${r.renderReader("response", model, emptyList())}")
					}
				}
			}
		}
	}

	private fun Indented.write(type: Model.Type, model: Model) {
		if (type.enumValues != null) {
			writeEnum(type, type.enumValues)
		} else {
			writeClass(type, model)
		}
	}

	private fun Indented.writeClass(type: Model.Type, model: Model) {

		val args = type.props
			.joinToString("") {
				val name = it.name.caseCamelToSnake()
				val argType = it.type.render(model)
				", $name: $argType"
			}

		// define the class
		val typeParams = type.typeParams
			.takeIf { it.isNotEmpty() }
		val parent =
			type.polymorphicSupertype
				?.let { "(${it.flatName}, metaclass=ABCMeta)" }
				?: run {
					typeParams
						?.let { params -> params.joinToString(",") { it.name } }
						?.let { "(Generic[$it])" }
						?: ""
				}
		writeln("class ${type.flatName}$parent:")
		indent {

			// copy the class kdocs, if any, into the Python docstring
			docstring(type.doc.textOrUndocumented)

			writeln()

			writeln("TYPE_ID: str = '${type.packageName}.${type.names}'")

			// write the constructor, if there's anything to construct
			val canInstantiate = type.polymorphicSubtypes.isEmpty()
			if (canInstantiate && type.props.isNotEmpty()) {

				writeln()

				writeln("def __init__(self$args) -> None:")
				indent {

					// write properties
					for (prop in type.props) {
						val name = prop.name.caseCamelToSnake()
						writeln("self.$name: ${prop.type.render(model)} = $name")
						docstring(prop.doc.textOrUndocumented)
					}

					// write the generic type ids, if needed
					typeParams?.let { typeParams ->
						writeln("self._generics: Dict[str,Optional[str]] = {")
						indent {
							for (param in typeParams) {
								writeln("'${param.name}': None")
							}
						}
						writeln("}")
					}
				}
			}

			// write the json serializer
			if (canInstantiate) {

				writeln()

				writeln("def to_json(self) -> Dict[str,Any]:")
				indent {

					docstring("Converts this class instance into JSON")

					// write the polymorphic serializers, if any
					for (typeParam in type.typeParams) {
						writeln()
						writeln("def writer_polymorphic_${typeParam.name}(val: Any) -> Any:")
						indent {
							writeln("type_id = self._generics['${typeParam.name}']")
							for ((i, ref) in typeParam.instances.withIndex()) {
								val cond = "type_id == '${ref.id}'"
								if (i == 0) {
									writeln("if $cond:")
								} else {
									writeln("elif $cond:")
								}
								indent {
									writeln("return ${ref.renderWriter("val", model, type.typeParams)}")
								}
							}
							writeln("else:")
							indent {
								writeln("raise Exception(f'Failed to serialize type, unrecognized type id `{type_id}` for type parameter ${typeParam.name}')")
							}
						}
					}

					writeln()

					writeln("return {")
					indent {

						if (type.polymorphicSerialization) {
							writeln("'type': '${type.packageName}.${type.names}',")
						}

						for (prop in type.props) {
							val name = prop.name.caseCamelToSnake()
							val value = prop.type.renderWriter("self.$name", model, type.typeParams)
							writeln("'${prop.name}': $value,")
						}
					}
					writeln("}")
				}

			} else {

				// superclasses need to define to_json as abstract so they delegate to subclasses
				writeln("@abstractmethod")
				writeln("def to_json(self) -> Dict[str,Any]:")
				indent {
					writeln("pass")
				}
			}

			writeln()

			// write the json deserializer
			writeln("@classmethod")
			writeln("def from_json(cls, json: Dict[str,Any], type_ids: Optional[Dict[str,str]] = None) -> ${type.parameterizedName}:")
			indent {

				docstring("Creates a new class instance from JSON")

				// write the polymorphic deserializers, if any
				for (typeParam in type.typeParams) {
					writeln()
					writeln("def reader_polymorphic_${typeParam.name}(json: Dict[str,Any], type_ids: Optional[Dict[str,str]] = type_ids) -> Any:")
					indent {

						writeln("if type_ids is None:")
						indent {
							writeln("raise Exception('${type.name}.from_json() failed because no type ids were given')")
						}
						writeln("try:")
						indent {
							writeln("type_id = type_ids['${typeParam.name}']")
						}
						writeln("except KeyError:")
						indent {
							writeln("raise Exception('${type.name}.from_json() failed because no type id was found for type parameter ${typeParam.name}')")
						}

						// TODO: look for type info in the JSON?

						for ((i, ref) in typeParam.instances.withIndex()) {
							val cond = "type_id == '${ref.id}'"
							if (i == 0) {
								writeln("if $cond:")
							} else {
								writeln("elif $cond:")
							}
							indent {
								writeln("return ${ref.renderReader("json", model, type.typeParams)}")
							}
						}
						writeln("else:")
						indent {
							writeln("raise Exception(f'Failed to deserialize type, unrecognized type id `{type_id}` for type parameter ${typeParam.name}')")
						}
					}
				}

				writeln()

				if (type.polymorphicSubtypes.isNotEmpty()) {

					// polymorphic supertype, need to do polymorphic deserialization of subtypes
					// look for 'type' key in json and match against the (exhaustive) list of possible subclasses
					// WARNING: failing to explicitly allow-list polymorphic types during deserialization is a
					//          very serious security vulnerability, so proceed with caution here!!
					writeln("cls_type = json['type']")
					for ((i, subtype) in type.polymorphicSubtypes.withIndex()) {
						val conditional =
							if (i == 0) {
								"if"
							} else {
								"elif"
							}
						writeln("$conditional cls_type == '${subtype.packageName}.${subtype.names}':")
						indent {
							writeln("return ${subtype.flatName}.from_json(json)")
						}
					}
					writeln("else:")
					indent {
						writeln("raise Exception(f'unrecognized polymorphic subtype {cls_type} for supertype ${type.flatName}')")
					}

				} else {

					writeln("return cls(")
					indent {
						for (prop in type.props) {

							// first, get read the value from json, returning None if the key is missing
							// NOTE: don't use x,y,k,v as scope variables here, since they already get used for collection iteration
							var expr = "optional_map(json.get('${prop.name}', None), lambda it: ${prop.type.renderReader("it", model, type.typeParams)})"

							// apply default value (if any) or bail if the type is not nullable
							val default = when (val default = prop.default) {
								null -> null
								is IntegerConstant -> default.value.toString()
								is StringConstant -> "'${default.value.replace("'", "\\'")}'"
								is DoubleConstant -> default.value.toString()
								is FloatConstant -> default.value.toString()
								is BooleanConstant -> when (default.value) {
									true -> "True"
									false -> "False"
								}
								is ComplexExpression -> when (default.value) {
									"null" -> "None"
									else -> throw Error("don't know how to handle property default complex expression: ${default.value}")
								}
								else -> throw Error("don't know how to handle property default: $default")
							}
							if (!prop.type.nullable) {
								if (default != null) {
									if (default == "None") {
										throw Error("Non-nullable type has a null default value")
									}
									expr = "none_map($expr, lambda: $default)"
								} else {
									expr = "none_raise($expr, lambda: KeyError('missing JSON key: ${prop.name}'))"
								}
							} else if (default != null && default != "None") {
								expr = "none_map($expr, lambda: $default)"
							}
							writeln("$expr,")
						}
					}
					writeln(")")
				}
			}

			// write the string serializer
			if (canInstantiate) {

				writeln()

				writeln("def serialize(self) -> str:")
				indent {
					writeln("return json.dumps(self.to_json())")
				}

			} else {

				// superclasses need to define serialize as abstract so they delegate to subclasses
				writeln("@abstractmethod")
				writeln("def serialize(self) -> str:")
				indent {
					writeln("pass")
				}
			}

			writeln()

			// write the string deserializer
			writeln("@classmethod")
			writeln("def deserialize(cls, serialized: str, type_ids: Optional[Dict[str,str]] = None) -> ${type.parameterizedName}:")
			indent {
				writeln("return ${type.flatName}.from_json(json.loads(serialized), type_ids)")
			}

			if (canInstantiate) {

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
		}

		// recurse, but write inner classes as top-level classes
		// NOTE: while Python itself technically supports nested classes,
		//       basically nothing else does (like freaking Sphinx!), so don't use them
		for (inner in type.inners) {
			writeln()
			writeln()
			write(inner, model)
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


private fun Model.TypeRef.render(model: Model): String {

	val params = params
		.takeIf { it.isNotEmpty() }

	fun List<Model.TypeRef>?.inner(i: Int): String =
		this
			?.getOrNull(i)
			?.render(model)
			?: throw NoSuchElementException("$name type has no parameter at $i")

	return when (packageName) {

		// convert basic types
		"kotlin" -> when (name) {
			"String" -> "str"
			"Int", "Long" -> "int"
			"Boolean" -> "bool"
			"Float", "Double" -> "float"
			"Any" -> "Any"
			else -> throw Error("Don't know how to handle kotlin type: $name")
		}

		// convert collection types
		"kotlin.collections" -> when (name) {
			"List" -> params
				?.let { "List[${it.inner(0)}]" }
				?: "List"
			"Set" -> params
				?.let { "Set[${it.inner(0)}]" }
				?: "Set"
			"Map" -> params
				?.let { "Dict[${it.inner(0)}, ${it.inner(1)}]" }
				?: "Dict"
			else -> throw Error("Don't know how to handle kotlin collection type: $name")
		}

		// handle our types
		in PACKAGES -> {
			if (aliased != null) {
				val alias = model.findTypeAlias(this)
					?: throw Error("Can't find type alias for type ref: $this")
				if (alias.isOption) {
					// HACKHACK: convert Kotlin Option types to Python Optional
					"Optional[${params.inner(0)}]"
				} else if (alias.isToString) {
					alias.render(model, this.params)
				} else {
					throw Error("Don't know how to render type alias: $alias")
				}
			} else {
				params
					?.let { "$flatName[${params.joinToString(",") { it.render(model) }}]" }
					?: flatName
			}
		}

		// handle type parameters
		"" -> {
			if (!parameter) {
				throw Error("Type $name has no package, but is not a type parameter")
			}
			name
		}

		// look for an external type
		else -> {
			val externalType = model.findExternalType(this)
				?: throw Error("Don't know how to handle type ref: $this")
			externalType.flatName
		}
	}
	// apply nullability
	.let {
		if (nullable) {
			"Optional[$it]"
		} else {
			it
		}
	}
}

private fun Model.TypeAlias.render(model: Model, paramRefs: List<Model.TypeRef>): String {

	if (typeParams.size != paramRefs.size) {
		throw Error("param refs (${paramRefs.size}) don't match type alias params (${typeParams.size})")
	}

	return if (paramRefs.isNotEmpty()) {
		"${name}_${paramRefs.joinToString("_") { it.render(model) }}"
	} else {
		name
	}
}

private fun Model.TypeRef.renderReader(expr: String, model: Model, typeParams: List<Model.Type.Param>): String {

	fun inner(i: Int, expr: String): String =
		params.getOrNull(i)
			?.renderReader(expr, model, typeParams)
			?: throw NoSuchElementException("$name type has no parameter at $i")

	return when (packageName) {

		// read basic types
		"kotlin" -> when (name) {
			// no translation needed, just cast the type
			"String" -> "cast(str, $expr)"
			"Int", "Long" -> "cast(int, $expr)"
			"Boolean" -> "cast(bool, $expr)"
			"Float", "Double" -> "cast(float, $expr)"
			else -> throw Error("Don't know how to read kotlin type: $name")
		}

		// convert collection types
		"kotlin.collections" -> when (name) {
			"List" -> "[${inner(0, "x")} for x in cast(List[Any], $expr)]"
			"Set" -> "{${inner(0, "x")} for x in cast(Set[Any], $expr)}"
			"Map" -> "{${inner(0, "k")}:${inner(1, "v")} for k,v in cast(Dict[str,Any], $expr)}"
			else -> throw Error("Don't know how to read kotlin collection type: $name")
		}

		// handle our types
		in PACKAGES -> {
			if (aliased != null) {
				val alias = model.findTypeAlias(this)
					?: throw Error("Can't find type alias for type ref: $this")
				if (alias.isOption) {
					// HACKHACK: convert Kotlin Option types to Python Optional
					"optional_map(option_unwrap($expr), lambda x: ${inner(0, "x")})"
				} else if (alias.isToString) {
					"cast(${alias.render(model, params)}, $expr)"
				} else {
					throw Error("Don't know how to render reader for type alias: $alias")
				}
			} else if (params.isNotEmpty()) {
				// handle type parameters
				val type = model.findType(this)
					?: throw Error("Don't have definition for type: $this")
				val p = (params zip type.typeParams)
					.joinToString(", ") { (param, typeParam) ->
						"'${typeParam.name}': '${param.id}'"
					}
				"$flatName.from_json($expr, type_ids={$p})"
			} else {
				"$flatName.from_json($expr)"
			}
		}

		// handle type parameters
		"" -> {
			if (!parameter) {
				throw Error("Type $name has no package, but is not a type parameter")
			}
			typeParams
				.find { it.name == name }
				?.let { param -> "reader_polymorphic_${param.name}($expr)" }
				?: throw Error("Parent type has no parameter named $name")
		}

		// look for an external type
		else -> {
			val externalType = model.findExternalType(this)
				?: throw Error("Don't know how to read type: $this")
			"${externalType.flatName}.from_json($expr)"
		}
	}
}

private fun Model.TypeRef.renderWriter(varname: String, model: Model, typeParams: List<Model.Type.Param>): String = resolveAliases().run {
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

			fun inner(i: Int, varname: String): String =
				params.getOrNull(i)
					?.renderWriter(varname, model, typeParams)
					?: throw NoSuchElementException("$name type has no parameter at $i")

			when (name) {
				"List", "Set" -> "[${inner(0, "x")} for x in $varname]"
				"Map" -> "[[${inner(0, "k")},${inner(1, "v")}] for k,v in $varname]"
				else -> throw Error("Don't know how to read kotlin collection type: $name")
			}
		}

		// handle our types
		in PACKAGES -> {
			if (aliased != null) {
				val alias = model.findTypeAlias(this)
					?: throw Error("Can't find type alias for type ref: $this")
				if (alias.isOption) {
					throw Error("Option types should be read-only")
				} else if (alias.isToString) {
					// no upcast needed for alias to str
					varname
				} else {
					throw Error("Don't know how to render reader for type alias: $alias")
				}
			} else {
				"$varname.to_json()"
			}
		}

		// handle type parameters
		"" -> {
			if (!parameter) {
				throw Error("Type $name has no package, but is not a type parameter")
			}
			typeParams
				.find { it.name == name }
				?.let { param -> "writer_polymorphic_${param.name}($varname)" }
				?: throw Error("Parent type has no parameter named $name")
		}

		// look for an external type
		else -> {
			val externalType = model.findExternalType(this)
				?: throw Error("Don't know how to write type: $this")
			"${externalType.flatName}.to_json()"
		}
	}
	// apply nullability
	.appendIf(nullable) {
		" if $varname is not None else None"
	}
}
