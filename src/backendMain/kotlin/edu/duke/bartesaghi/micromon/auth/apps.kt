package edu.duke.bartesaghi.micromon.auth

import de.mkammerer.argon2.Argon2Factory
import edu.duke.bartesaghi.micromon.base62Decode
import edu.duke.bartesaghi.micromon.base62Encode
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getListOfStrings
import edu.duke.bartesaghi.micromon.mongo.getStringId
import edu.duke.bartesaghi.micromon.services.AppPermissionData
import edu.duke.bartesaghi.micromon.services.AppTokenData
import edu.duke.bartesaghi.micromon.services.AppTokenRequestData
import org.bson.Document
import java.security.SecureRandom


/**
 * Doesn't save the actual token itself (clients keep that, hopefully securely),
 * but stores all the metadata surrounding the token.
 */
class AppTokenInfo(
	val tokenId: String,
	val userId: String,
	val appName: String,
	/** the hash of the token */
	val hash: String,
	val appPermissionIds: List<String>
) {

	companion object {

		fun fromDoc(doc: Document) =
			AppTokenInfo(
				tokenId = doc.getStringId("_id"),
				userId = doc.getString("userId"),
				appName = doc.getString("appName"),
				hash = doc.getString("hash"),
				appPermissionIds = doc.getListOfStrings("appPermissionIds")
					?: emptyList()
			)

		/**
		 * An instance of Argon2 that's only suitable for hashing app tokens.
		 * This should *NOT* be used to hash passwords!
		 */
		private val argon =
			Argon2Factory.create(
				Argon2Factory.Argon2Types.ARGON2i,
				8, // salt length (don't need a big salt here, since the tokens are large CSPRNs)
				32 // hash length
			)

		/**
		 * Hash an app token using weak Argon2 params.
		 * This should *NOT* be used to hash passwords!
		 */
		private fun hashToken(token: ByteArray): String =
			// login tokens are chosen by the server and therefore make very strong passwords
			// therefore, even "weak" hashes are sufficient to protect them
			// we don't even need to add any extra salt
			// https://security.stackexchange.com/questions/63435/why-use-an-authentication-token-instead-of-the-username-password-per-request/63438#63438
			// basically, any hash function with preimage resistance will work fine here
			argon.hash(
				1, // iterations
				128, // memory, in KiB
				1, // threads
				token
			)

		fun generate(userId: String, appName: String, permissions: List<AppPermission>): Pair<String,AppTokenInfo> {

			// generate the raw token
			// 32 bytes (256 bits) of entropy is more than enough security for the forseeable future
			val token = ByteArray(32)
			SecureRandom().nextBytes(token)

			// hash it
			val hash = hashToken(token)

			val permissionIds = permissions.map { it.appPermissionId }
			val info = Database.appTokens.create { tokenId ->
				AppTokenInfo(tokenId, userId, appName, hash, permissionIds)
			}

			return token.base62Encode() to info
		}

		fun getAll(userId: String): List<AppTokenInfo> =
			Database.appTokens.getAll(userId)

		fun get(tokenId: String): AppTokenInfo? =
			Database.appTokens.get(tokenId)

		fun find(userId: String, token: String): AppTokenInfo? {
			val rawToken = try {
				token.base62Decode()
			} catch (t: Throwable) {
				return null
			}
			return Database.appTokens.find(userId) { hash ->
				argon.verify(hash, rawToken)
			}
		}
	}


	fun toDoc() = Document().also { doc ->
		doc["userId"] = userId
		doc["appName"] = appName
		doc["hash"] = hash
		doc["appPermissionIds"] = appPermissionIds
	}

	fun toData() = AppTokenData(
		tokenId,
		appName,
		appPermissionIds
			.map { AppPermission[it].toData(it) }
	)

	fun revoke() {
		Database.appTokens.delete(this)
	}
}


data class AppPermission(
	val appPermissionId: String,
	/** intended for users to understand */
	val description: String,
	val endpoints: List<String>
) {

	companion object {

		private val permissions = HashMap<String,AppPermission>()

		private fun AppPermission.add(): AppPermission {
			if (permissions.containsKey(appPermissionId)) {
				throw IllegalStateException("app permissions with id=$appPermissionId already defined")
			}
			permissions[appPermissionId] = this
			return this
		}

		val ADMIN = AppPermission(
			"admin",
			"""
				|Use your administrator access, if you have it.
				|This permission would allow the app to, for example, take actions
				|on behalf of others users using the administrator permission on your account. 
			""".trimMargin(),
			emptyList()
		).add()

		val PROJECT_LIST = AppPermission(
			"project_list",
			"""
				|See a list of all of your projects.
			""".trimMargin(),
			listOf(
				"/kv/projects/list"
			)
		).add()

		val PROJECT_REALTIME = AppPermission(
			"project_listen",
			"""
				|Listen to your project and its running jobs in real-time.
			""".trimMargin(),
			listOf(
				"/ws/project"
			)
		).add()

		// TODO: expose interesting parts of the API

		/** endpoints everyone can access even without a valid token */
		val OPEN_ENDPOINTS = listOf(
			"/kv/apps/version",
			"/kv/apps/requestToken"
		)


		operator fun get(appPermissionId: String): AppPermission? =
			permissions[appPermissionId]

		fun getOrThrow(appPermissionId: String): AppPermission =
			get(appPermissionId)
				?: throw NoSuchElementException("no app permission $appPermissionId")

		fun toDataUnknown(appPermissionId: String) =
			AppPermissionData(
				appPermissionId,
				null
			)
	}

	fun toData() = AppPermissionData(
		appPermissionId,
		description
	)
}

fun AppPermission?.toData(appPermissionId: String) =
	this?.toData()
		?: AppPermission.toDataUnknown(appPermissionId)


class AppTokenRequest(
	val requestId: String,
	val userId: String,
	val appName: String,
	val appPermissionIds: List<String>
) {

	companion object {

		fun fromDoc(doc: Document) =
			AppTokenRequest(
				requestId = doc.getStringId("_id"),
				userId = doc.getString("userId"),
				appName = doc.getString("appName"),
				appPermissionIds = doc.getListOfStrings("appPermissionIds")
					?: emptyList()
			)

		fun create(userId: String, appName: String, permissionIds: List<String>) =
			Database.appTokenRequests.create { requestId ->
				AppTokenRequest(requestId, userId, appName, permissionIds)
			}

		fun getAll(userId: String): List<AppTokenRequest> =
			Database.appTokenRequests.getAll(userId)

		fun get(requestId: String): AppTokenRequest? =
			Database.appTokenRequests.get(requestId)

	}

	fun toDoc() = Document().also { doc ->
		doc["userId"] = userId
		doc["appName"] = appName
		doc["appPermissionIds"] = appPermissionIds
	}

	fun toData() = AppTokenRequestData(
		requestId,
		appName,
		appPermissionIds
			.map { AppPermission[it].toData(it) }
	)

	fun reject() {
		Database.appTokenRequests.delete(this)
	}

	fun accept(): Pair<String,AppTokenInfo> {
		val permissions = appPermissionIds
			.map { AppPermission.getOrThrow(it) }
		val (token, info) = AppTokenInfo.generate(userId, appName, permissions)
		Database.appTokenRequests.delete(this)
		return token to info
	}
}
