package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.BuildData
import edu.duke.bartesaghi.micromon.User
import edu.duke.bartesaghi.micromon.auth.*
import edu.duke.bartesaghi.micromon.sanitizeExceptions
import io.ktor.application.*
import io.kvision.remote.ServiceException


actual class AppsService : IAppsService, Service {

	@Inject
	override lateinit var call: ApplicationCall


	private fun User.authTokenRequestOrThrow(requestId: String): AppTokenRequest {

		val request = AppTokenRequest.get(requestId)
			?: throw ServiceException("app token request not found")

		authUserOrThrow(request.userId)

		return request
	}

	private fun User.authTokenOrThrow(tokenId: String): AppTokenInfo {

		val token = AppTokenInfo.get(tokenId)
			?: throw ServiceException("app token not found")

		authUserOrThrow(token.userId)

		return token
	}

	override suspend fun versions(): VersionData = sanitizeExceptions {

		call.auth()

		VersionData(
			BuildData.version,
			BuildData.apiVersion
		)
	}

	override suspend fun requestToken(userId: String, appName: String, appPermissionIds: List<String>): AppTokenRequestData = sanitizeExceptions {

		call.auth()

		// validate the permission ids
		for (appPermissionId in appPermissionIds) {
			AppPermissions[appPermissionId]
				?: throw ServiceException("no app permission id: $appPermissionId")
		}

		AppTokenRequest.create(userId, appName, appPermissionIds)
			.toData()
	}

	override suspend fun tokenRequests(userId: String): List<AppTokenRequestData> = sanitizeExceptions {

		call.authOrThrow().authUserOrThrow(userId)

		AppTokenRequest.getAll(userId)
			.map { it.toData() }
	}

	override suspend fun acceptTokenRequest(requestId: String): AppTokenRequestAcceptance = sanitizeExceptions {

		val request = call.authOrThrow().authTokenRequestOrThrow(requestId)

		val (token, info) = request.accept()
		AppTokenRequestAcceptance(token, info.toData())
	}

	override suspend fun rejectTokenRequest(requestId: String) = sanitizeExceptions {

		val request = call.authOrThrow().authTokenRequestOrThrow(requestId)

		request.reject()
	}

	override suspend fun appTokens(userId: String): List<AppTokenData> = sanitizeExceptions {

		call.authOrThrow().authUserOrThrow(userId)

		AppTokenInfo.getAll(userId)
			.map { it.toData() }
	}

	override suspend fun revokeToken(tokenId: String) = sanitizeExceptions {

		val token = call.authOrThrow().authTokenOrThrow(tokenId)

		token.revoke()
	}
}
