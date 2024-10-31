package edu.duke.bartesaghi.micromon

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import edu.duke.bartesaghi.micromon.mongo.Database
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.*
import org.bson.Document
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import kotlin.reflect.KSuspendFunction1


/*
 * tragically, KVision's JSON-RPC 2.0 isn't completely compliant with the spec,
 * so the python client can't read the responses
 * So we need to implement our own response format here
 * Also, KVision's system uses a nested serializaton system for params and responses,
 * which is hard to use on the python side
 */


interface JsonRpcResponse

class JsonRpcSuccess(
	val result: JsonNode? = null
) : JsonRpcResponse

class JsonRpcFailure(
	val message: String,
	val code: Int = 0,
	val data: String? = null
) : JsonRpcResponse


object JsonRpc {

	val nodes = JsonNodeFactory(false)

	val token: String by lazy {
		getOrMakeToken()
			.also {
				// in debug mode, print out the token so we can run test scripts against the RPC endpoints
				// but never print out the token in production mode, that would be a security risk
				if (Config.instance.web.debug) {
					println("JsonRpc token: $it")
				}
			}
	}

	private fun getOrMakeToken(): String {

		// check the database for an existing token
		val token = Database.instance.settings.get("JsonRpc")?.getString("token")
		if (token != null) {
			return token
		}

		// still don't have a token, generate a new one (securely)
		val bytes = ByteArray(64)
		SecureRandom().nextBytes(bytes)
		val newToken = bytes.base62Encode()

		// update the database
		Database.instance.settings.set("JsonRpc", Document().apply {
			set("token", newToken)
		})

		return newToken
	}

	fun init(routing: Routing, endpoint: String, methods: Map<String,KSuspendFunction1<ObjectNode,JsonRpcResponse>>) {

		// return some kind of response if we hit this endpoint in a browser
		routing.get(endpoint) {
			// yes, this is a real HTTP status code =P
			// https://en.wikipedia.org/wiki/List_of_HTTP_status_codes
			call.respond(HttpStatusCode(418, "I'm a teapot"))
		}

		routing.post(endpoint) {

			val log = LoggerFactory.getLogger("JsonRpc.$endpoint")

			// receive the request as a raw json string
			val jsonstr = call.receive<String>()

			// convert to a json tree so we can read values without having to deal with class serialization
			@Suppress("BlockingMethodInNonBlockingContext")
			val json = ObjectMapper().readTree(jsonstr)

			// try to get the id first, since we need that to send any kind of response
			val id = try {
				json.asObject().getIntOrThrow("id")
			} catch (ex: BadJsonRpcRequestException) {
				call.respond(
					0, // no id, just use zero and hope nothing bad happens
					JsonRpcFailure("no request id")
				)
				return@post
			}

			try {

				val request = json.asObject()

				// authenticate the request
				val token = request.getStringOrThrow("token")
				if (token != this@JsonRpc.token) {
					log.info("access denied, invalid token: $token")
					call.respond(id, JsonRpcFailure("access denied"))
					return@post
				}

				// get the params
				val params = request.getObject("params")
					?: nodes.objectNode()

				// actually call the method
				val method = request.getStringOrThrow("method")
				val response = methods[method]
					?.invoke(params)
					?: throw BadJsonRpcRequestException("no method: $method")

				// send back the response to the client
				call.respond(id, response)

			} catch (ex: BadJsonRpcRequestException) {

				// the client did something wrong, try to let them know
				call.respond(id, JsonRpcFailure(ex.message ?: "Bad Request"))

			} catch (t: Throwable) {

				// internal error, log and sanitize it before telling the client
				log.error("request: $json", t)
				call.respond(id, JsonRpcFailure("Internal Error"))
			}
		}
	}

	private suspend fun ApplicationCall.respond(id: Int, response: JsonRpcResponse) {

		val rpcResponse = nodes.objectNode().apply {

			put("id", id)
			put("jsonrpc", "2.0")

			when (response) {

				is JsonRpcSuccess -> {
					set<JsonNode>("result", response.result)
				}

				is JsonRpcFailure -> {
					putObject("error").apply {
						put("code", response.code)
						put("message", response.message)
						if (response.data != null) {
							put("data", response.data)
						}
					}
				}
			}
		}

		// serialize and send the response
		@Suppress("BlockingMethodInNonBlockingContext")
		val str = ObjectMapper().writeValueAsString(rpcResponse)
		respond(str)
	}
}


class BadJsonRpcRequestException(msg: String, context: String? = null) : IllegalArgumentException(
	if (context != null) {
		"$msg\n\tcontext: $context"
	} else {
		msg
	}
)


fun JsonNode.asObject(): ObjectNode =
	takeIf { it.isObject }
		?.let { it as ObjectNode }
		?: throw BadJsonRpcRequestException("json node is not an object")

fun JsonNode.asArray(): ArrayNode =
	takeIf { it.isArray }
		?.let { it as ArrayNode }
		?: throw BadJsonRpcRequestException("json node is not an array")

fun ObjectNode.getObject(key: String): ObjectNode? {
	val value = get(key)
		?.takeIf { !it.isNull }
		?: return null
	if (!value.isObject) {
		throw BadJsonRpcRequestException("$key is not an object")
	}
	return value as ObjectNode
}
fun ObjectNode.getObjectOrThrow(key: String): ObjectNode =
	getObject(key) ?: throw BadJsonRpcRequestException("missing object: $key")

fun ObjectNode.getArray(key: String): ArrayNode? {
	val value = get(key)
		?.takeIf { !it.isNull }
		?: return null
	if (!value.isArray) {
		throw BadJsonRpcRequestException("$key is not an array")
	}
	return value as ArrayNode
}
fun ObjectNode.getArrayOrThrow(key: String): ArrayNode =
	getArray(key) ?: throw BadJsonRpcRequestException("missing array: $key")

fun ObjectNode.getBool(key: String): Boolean? {
	val value = get(key)
		?.takeIf { !it.isNull }
		?: return null
	if (!value.isBoolean) {
		throw BadJsonRpcRequestException("$key is not a bool")
	}
	return value.booleanValue()
}
fun ObjectNode.getBoolOrThrow(key: String): Boolean =
	getBool(key) ?: throw BadJsonRpcRequestException("missing bool: $key")

fun ObjectNode.getInt(key: String): Int? {
	val value = get(key)
		?.takeIf { !it.isNull }
		?: return null
	if (!value.isIntegralNumber) {
		throw BadJsonRpcRequestException("$key is not an integer")
	}
	return value.intValue()
}
fun ObjectNode.getIntOrThrow(key: String): Int =
	getInt(key) ?: throw BadJsonRpcRequestException("missing int: $key")

fun ObjectNode.getLong(key: String): Long? {
	val value = get(key)
		?.takeIf { !it.isNull }
		?: return null
	if (!value.isIntegralNumber) {
		throw BadJsonRpcRequestException("$key is not a long")
	}
	return value.longValue()
}
fun ObjectNode.getLongOrThrow(key: String): Long =
	getLong(key) ?: throw BadJsonRpcRequestException("missing long: $key")

fun ObjectNode.getDouble(key: String): Double? {
	val value = get(key)
		?.takeIf { !it.isNull }
		?: return null
	if (!value.isNumber) {
		throw BadJsonRpcRequestException("$key is not an integer")
	}
	return value.doubleValue()
}
fun ObjectNode.getDoubleOrThrow(key: String): Double =
	getDouble(key) ?: throw BadJsonRpcRequestException("missing double: $key")

fun ObjectNode.getString(key: String): String? {
	val value = get(key)
		?.takeIf { !it.isNull }
		?: return null
	if (!value.isTextual) {
		throw BadJsonRpcRequestException("$key is not a string")
	}
	return value.textValue()
}
fun ObjectNode.getStringOrThrow(key: String): String =
	getString(key) ?: throw BadJsonRpcRequestException("missing string: $key")

fun ObjectNode.getStrings(key: String): List<String>? {
	val value = get(key)
		?.takeIf { !it.isNull }
		?: return null
	if (!value.isArray) {
		throw BadJsonRpcRequestException("$key is not an array")
	}
	return value.mapIndexed { i, entry ->
		if (!entry.isTextual) {
			throw BadJsonRpcRequestException("[$i] is not a string", key)
		}
		entry.textValue()
	}
}
fun ObjectNode.getStringsOrThrow(key: String) =
	getStrings(key) ?: throw BadJsonRpcRequestException("missing strings: $key")


fun ArrayNode.indices(): IntRange =
	(0 until size())

fun ArrayNode.getOrThrow(index: Int, context: String? = null): JsonNode =
	get(index) ?: throw BadJsonRpcRequestException("$index out of array range [0,${size()})", context)

fun ArrayNode.getObjectOrThrow(index: Int, context: String? = null): ObjectNode =
	getOrThrow(index)
		.takeIf { it.isObject }
		?.asObject()
		?: throw BadJsonRpcRequestException("[$index] is not an object, it's a(n) ${getOrThrow(index).nodeType}", context)

fun ArrayNode.getArrayOrThrow(index: Int, context: String? = null): ArrayNode =
	getOrThrow(index)
		.takeIf { it.isArray }
		?.asArray()
		?: throw BadJsonRpcRequestException("[$index] is not an array, it's a(n) ${getOrThrow(index).nodeType}", context)

fun ArrayNode.getIntOrThrow(index: Int, context: String? = null): Int =
	getOrThrow(index)
		.takeIf { it.isIntegralNumber }
		?.intValue()
		?: throw BadJsonRpcRequestException("[$index] is not an int, it's a(n) ${getOrThrow(index).nodeType}", context)

fun ArrayNode.getDoubleOrThrow(index: Int, context: String? = null): Double =
	getOrThrow(index)
		.takeIf { it.isNumber }
		?.doubleValue()
		?: throw BadJsonRpcRequestException("[$index] is not a double, it's a(n) ${getOrThrow(index).nodeType}", context)

fun ArrayNode.getNumberAsIntOrThrow(index: Int, context: String? = null): Int =
	getOrThrow(index)
		.takeIf { it.isNumber }
		?.intValue()
		?: throw BadJsonRpcRequestException("[$index] is not an int, it's a(n) ${getOrThrow(index).nodeType}", context)

fun ArrayNode.getStringOrThrow(index: Int, context: String? = null): String =
	getOrThrow(index)
		.takeIf { it.isTextual }
		?.textValue()
		?: throw BadJsonRpcRequestException("[$index] is not a string, it's a(n) ${getOrThrow(index).nodeType}", context)

fun ArrayNode.getStringsOrThrow(index: Int, context: String? = null): List<String> =
	getArrayOrThrow(index, context)
		.mapIndexed { i, entry ->
			if (!entry.isTextual) {
				throw BadJsonRpcRequestException("[$index][$i] is not a string, it's a(n) ${getOrThrow(index).nodeType}", context)
			}
			entry.textValue()
		}

fun ArrayNode.toListOfDoubles(): List<Double> {
	return map {
		it.doubleValue()
	}
}

fun ArrayNode.toListOfListOfDoubles(): List<List<Double>> {
	return map {
		it.asArray().map { innerIt ->
			innerIt.doubleValue()
		}
	}
}
