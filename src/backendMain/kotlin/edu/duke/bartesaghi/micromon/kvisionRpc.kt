package edu.duke.bartesaghi.micromon

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.kvision.annotations.KVBindingRoute
import io.kvision.remote.JsonRpcRequest
import io.kvision.remote.JsonRpcResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation


class KVisionServices(val url: String) {

	fun url(path: String): String =
		if (path.startsWith('/')) {
			"$url$path"
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

	// TODO: other functions for more args?
}
