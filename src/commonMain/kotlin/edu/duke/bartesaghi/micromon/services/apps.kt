package edu.duke.bartesaghi.micromon.services

import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
@ExportService("Apps")
/**
 * Service providing functions specific to apps,
 * mainly requesting and managing app tokens
 */
interface IAppsService {

	@ExportServiceFunction(AppPermission.Open)
	@KVBindingRoute("apps/version")
	/**
	 * Returns the version number of NextPYP
	 */
	suspend fun version(): String

	@ExportServiceFunction(AppPermission.Open)
	@KVBindingRoute("apps/requestToken")
	/**
	 * Request an app token for your app on behalf of a specific user.
	 *
	 * Once requested, the user will need to approve the token request on the website.
	 * After approval, the user can copy the app token (it's a string) into your app.
	 */
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