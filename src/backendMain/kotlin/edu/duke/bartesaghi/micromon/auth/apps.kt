package edu.duke.bartesaghi.micromon.auth

import de.mkammerer.argon2.Argon2Factory
import edu.duke.bartesaghi.micromon.Resources
import edu.duke.bartesaghi.micromon.base62Decode
import edu.duke.bartesaghi.micromon.base62Encode
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getListOfStrings
import edu.duke.bartesaghi.micromon.mongo.getStringId
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.annotations.KVBindingRoute
import org.bson.Document
import org.reflections.Reflections
import org.reflections.scanners.Scanners
import java.security.SecureRandom
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.memberProperties


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


object AppEndpoints {

	private val endpoints = HashMap<String,ArrayList<String>>()

	init {

		// look for regular services
		val services = Reflections(Resources.packageName)
			.get(
				Scanners.TypesAnnotated.with(ExportService::class.java)
					.asClass<Class<*>>()
			)
			.map { it.kotlin }
		for (service in services) {

			for (func in service.functions) {
				val export = func.findAnnotation<ExportServiceFunction>()
					?: continue
				val route = func.findAnnotation<KVBindingRoute>()
					?: throw NoSuchElementException("Exported service function has no KVBindingRoute annotation")
				val endpoint = "/kv/${route.route}"
				add(endpoint, export.permission)
			}
		}

		// look for realtime services
		for (prop in RealTimeServices::class.memberProperties) {
			val export = prop.findAnnotation<ExportRealtimeService>()
				?: continue
			val endpoint = "/ws/${prop.name}"
			add(endpoint, export.permission)
		}
	}

	fun init() {
		// stub function to make sure init block gets called at startup
	}

	private fun add(endpoint: String, permission: AppPermission) {
		endpoints
			.getOrPut(permission.appPermissionId) { ArrayList() }
			.add(endpoint)
	}

	operator fun get(id: String): List<String> =
		endpoints[id] ?: emptyList()
}

fun AppPermission.endpoints(): List<String> =
	AppEndpoints[appPermissionId]
