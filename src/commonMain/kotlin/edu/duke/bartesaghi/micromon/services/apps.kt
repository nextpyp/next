package edu.duke.bartesaghi.micromon.services

import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface IAppsService {

	@KVBindingRoute("apps/version")
	suspend fun version(): String

	@KVBindingRoute("apps/requestToken")
	suspend fun requestToken(userId: String, appName: String, appPermissionIds: List<String>): AppTokenRequestData

	@KVBindingRoute("apps/tokenRequests")
	suspend fun tokenRequests(userId: String): List<AppTokenRequestData>

	@KVBindingRoute("apps/acceptTokenRequest")
	suspend fun acceptTokenRequest(requestId: String): String

	@KVBindingRoute("apps/rejectTokenRequest")
	suspend fun rejectTokenRequest(requestId: String)

	@KVBindingRoute("apps/appTokens")
	suspend fun appTokens(userId: String): List<AppTokenData>

	@KVBindingRoute("apps/revokeToken")
	suspend fun revokeToken(tokenId: String)
}


@Serializable
data class AppTokenRequestData(
	val requestId: String,
	val appName: String,
	val permissions: List<AppPermissionData>
)


@Serializable
data class AppTokenData(
	val tokenId: String,
	val appName: String,
	val permissions: List<AppPermissionData>
)


@Serializable
data class AppPermissionData(
	val appPermissionId: String,
	val description: String?
)
