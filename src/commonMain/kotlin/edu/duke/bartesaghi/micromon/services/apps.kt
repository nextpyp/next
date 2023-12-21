package edu.duke.bartesaghi.micromon.services

import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
@ExportService("Apps")
interface IAppsService {

	@ExportServiceFunction
	@KVBindingRoute("apps/version")
	suspend fun version(): String

	@ExportServiceFunction
	@KVBindingRoute("apps/requestToken")
	suspend fun requestToken(userId: String, appName: String, appPermissionIds: List<String>): AppTokenRequestData

	@KVBindingRoute("apps/tokenRequests")
	suspend fun tokenRequests(userId: String): List<AppTokenRequestData>

	@KVBindingRoute("apps/acceptTokenRequest")
	suspend fun acceptTokenRequest(requestId: String): AppTokenRequestAcceptance

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


@Serializable
data class AppTokenRequestAcceptance(
	val token: String,
	val tokenInfo: AppTokenData
)