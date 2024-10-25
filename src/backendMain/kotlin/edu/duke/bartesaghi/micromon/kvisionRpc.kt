package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.services.RealTimeC2S
import edu.duke.bartesaghi.micromon.services.RealTimeS2C
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.kvision.annotations.KVBindingRoute
import io.kvision.remote.JsonRpcRequest
import io.kvision.remote.JsonRpcResponse
import kotlinx.coroutines.channels.ChannelIterator
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.time.Duration


class KVisionServices(val hostname: String, val port: Int) {

	fun url(path: String, protocol: String = "http"): String =
		if (path.startsWith('/')) {
			"$protocol://$hostname:$port$path"
		} else {
			throw IllegalArgumentException("Paths must be absolute")
		}

	suspend fun get(path: String): HttpResponse =
		HttpClient()
			.request<HttpStatement>(url(path)) {
				method = HttpMethod.Get
			}
			.execute()

	suspend fun post(path: String, body: String, contentType: ContentType? = null): HttpResponse =
		HttpClient()
			.request<HttpStatement>(url(path)) {
				method = HttpMethod.Post
				this.body = body
				contentType?.let { this.contentType(it) }
			}
			.execute()

	suspend fun rpc(path: String, args: List<String>): String {

		// implement a client for KVisions pecuiliar flavor of JSONRPC
		val rpcRequest = JsonRpcRequest(
			id = 0,
			method = "ingnored",
			params = args
		)
		val httpResponse = post(path, Json.encodeToString(rpcRequest), ContentType.Application.Json)
		if (httpResponse.status.value != 200) {
			throw Error("HTTP Error: ${httpResponse.status.value}")
		}

		val rpcResponse = Json.decodeFromString<JsonRpcResponse>(httpResponse.readText())
		if (rpcResponse.error != null) {
			throw Error("RPC Error: ${rpcResponse.exceptionType}: ${rpcResponse.error}")
		}

		return rpcResponse.result ?: ""
	}

	inline fun <reified S:Any> route(func: Function<*>): String {

		// find the `KFunction` in the interface that corresponds to this `Function` so we can get the annotations
		// the `Function` (eg, IFace::func) is a compiler construct and not the real function object we can use for reflection
		val f = S::class.declaredFunctions
			.find { it == func }
			?: throw NoSuchElementException("Failed to find oringial function")

		val route = f.findAnnotation<KVBindingRoute>()
			?.route
			?: throw NoSuchElementException("Failed to get service function route: ${S::class.simpleName}.${f.name} missing KVBindingRoute annotation")

		return "/kv/$route"
	}

	suspend inline fun <reified S:Any, reified R> rpc(noinline func: suspend S.() -> R): R {
		return rpc(route<S>(func), listOf(
		)).let { Json.decodeFromString(it) }
	}

	suspend inline fun <reified S:Any, reified A1, reified R> rpc(noinline func: suspend S.(A1) -> R, a1: A1): R {
		return rpc(route<S>(func), listOf(
			Json.encodeToString(a1)
		)).let { Json.decodeFromString(it) }
	}

	suspend inline fun <reified S:Any, reified A1, reified A2, reified R> rpc(noinline func: suspend S.(A1, A2) -> R, a1: A1, a2: A2): R {
		return rpc(route<S>(func), listOf(
			Json.encodeToString(a1),
			Json.encodeToString(a2)
		)).let { Json.decodeFromString(it) }
	}

	suspend inline fun <reified S:Any, reified A1, reified A2, reified A3, reified R> rpc(noinline func: suspend S.(A1, A2, A3) -> R, a1: A1, a2: A2, a3: A3): R {
		return rpc(route<S>(func), listOf(
			Json.encodeToString(a1),
			Json.encodeToString(a2),
			Json.encodeToString(a3)
		)).let { Json.decodeFromString(it) }
	}

	suspend inline fun <reified S:Any, reified A1, reified A2, reified A3, reified A4, reified R> rpc(noinline func: suspend S.(A1, A2, A3, A4) -> R, a1: A1, a2: A2, a3: A3, a4: A4): R {
		return rpc(route<S>(func), listOf(
			Json.encodeToString(a1),
			Json.encodeToString(a2),
			Json.encodeToString(a3),
			Json.encodeToString(a4)
		)).let { Json.decodeFromString(it) }
	}

	suspend inline fun <reified S:Any, reified A1, reified A2, reified A3, reified A4, reified A5, reified R> rpc(noinline func: suspend S.(A1, A2, A3, A4, A5) -> R, a1: A1, a2: A2, a3: A3, a4: A4, a5: A5): R {
		return rpc(route<S>(func), listOf(
			Json.encodeToString(a1),
			Json.encodeToString(a2),
			Json.encodeToString(a3),
			Json.encodeToString(a4),
			Json.encodeToString(a5)
		)).let { Json.decodeFromString(it) }
	}

	suspend fun <R> websocket(path: String, block: suspend (DefaultClientWebSocketSession) -> R): R {
		HttpClient(CIO) {
			install(WebSockets) {
				// no configuration needed here
			}
		}.use { client ->
			var result: R? = null
			client.webSocket(url(path, protocol = "ws")) {
				result = block(this)
			}
			return result
				?: throw NoSuchElementException("No result: websocket() returned without calling function argument")
		}
	}
}



fun Frame.toMessage(): RealTimeS2C {

	if (!fin) {
		throw Error("Frame is fragmented... shouldn't happen on a non-raw KTor websocket handler")
	}

	return when (this) {
		is Frame.Text -> RealTimeS2C.fromJson(data.toString(Charsets.UTF_8))
		else -> throw Error("not a text frame")
	}
}

suspend inline fun <reified M:RealTimeC2S> SendChannel<Frame>.sendMessage(msg: M) {
	send(Frame.Text(msg.toJson()))
}

fun <I,O> ChannelIterator<I>.map(mapper: (I) -> O): ChannelIterator<O> =
	object : ChannelIterator<O> {
		override suspend fun hasNext() = this@map.hasNext()
		override fun next() = mapper(this@map.next())
	}

interface ReceiveChannelIterator<T> {
	operator fun iterator(): ChannelIterator<T>
}

fun ReceiveChannel<Frame>.messages(): ReceiveChannelIterator<RealTimeS2C> =
	object : ReceiveChannelIterator<RealTimeS2C> {
		override operator fun iterator() =
			this@messages.iterator().map { it.toMessage() }
	}

/**
 * Waits for the next message and returns it.
 * Throws an excepion if the message has an unexpected type.
 * Returns null if the connection was closed.
 */
suspend inline fun <reified M:RealTimeS2C> ReceiveChannel<Frame>.receiveMessage(): M =
	when (val msg = receive().toMessage()) {
		is M -> msg
		else -> throw UnexpectedMessageException(msg)
	}

class UnexpectedMessageException(val msg: Any) : IllegalArgumentException("message type: ${msg::class.simpleName}")


suspend inline fun <reified M:RealTimeS2C> ReceiveChannel<Frame>.waitForMessage(timeout: Duration, crossinline onMsg: (RealTimeS2C) -> Unit = {}): M? =
	withTimeoutOrNull(timeout) {
		for (msg in messages()) {
			if (msg is M) {
				return@withTimeoutOrNull msg
			} else {
				onMsg(msg)
			}
		}
		throw Error("Failed to wait for ${M::class.simpleName} message, no more messages")
	}
