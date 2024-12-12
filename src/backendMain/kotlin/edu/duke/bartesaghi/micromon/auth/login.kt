package edu.duke.bartesaghi.micromon.auth

import de.mkammerer.argon2.Argon2Factory
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.services.AppPermission
import edu.duke.bartesaghi.micromon.services.AuthType
import io.ktor.application.ApplicationCall
import io.ktor.request.*
import io.ktor.sessions.clear
import io.ktor.sessions.get
import io.ktor.sessions.sessions
import io.ktor.sessions.set
import java.security.SecureRandom



/**
 * An instance of Argon2 that's only suitable for hashing login tokens.
 * This should *NOT* be used to hash passwords!
 */
private val argonForTokens =
	Argon2Factory.create(
		Argon2Factory.Argon2Types.ARGON2i,
		8, // salt length (don't need a big salt here, since the tokens are large CSPRNs)
		32 // hash length
	)


/**
 * Hash a login token using weak Argon2 params.
 * This should *NOT* be used to hash passwords!
 */
private fun hashToken(token: ByteArray): String =
	// login tokens are chosen by the server and therefore make very strong passwords
	// therefore, even "weak" hashes are sufficient to protect them
	// we don't even need to add any extra salt
	// https://security.stackexchange.com/questions/63435/why-use-an-authentication-token-instead-of-the-username-password-per-request/63438#63438
	// basically, any hash function with preimage resistance will work fine here
	argonForTokens.hash(
		1, // iterations
		128, // memory, in KiB
		1, // threads
		token
	)


fun generateLoginToken(userId: String): ByteArray {

	// generate the login token
	// since these tokens are not real passwords and can never protect other resources (like a users bank account),
	// we don't need to take any in-memory precautions to protect these tokens from server administrators
	val token = ByteArray(64)
	// NOTE: lol, 64 bytes (512 bits) is probably a lot more entropy than we actually need here, but it's cheap, so who cares?
	SecureRandom().nextBytes(token)

	// save the hash
	Database.instance.users.addTokenHash(userId, hashToken(token))

	return token
}

fun verifyLoginToken(userId: String, token: ByteArray?): Boolean {

	token ?: return false

	val tkhashes = Database.instance.users.getTokenHashes(userId)
		// randomize the order of the hashes to hopefully keep the
		// JVM JIT from profiling and optimizing away any comparisions
		.shuffled()

	// attempt to verify all of the hashes in constant time
	var numVerified = 0
	for (tkhash in tkhashes) {
		if (argonForTokens.verify(tkhash, token)) {
			numVerified += 1
		}
	}
	// NOTE: the token hash params are cheap, so this loop should
	// hopefully go fast even for a user with a handful of hashes

	return numVerified >= 1
}

fun revokeLoginToken(userId: String, token: ByteArray) {
	Database.instance.users.removeTokenHashes(userId) { tkhash ->
		argonForTokens.verify(tkhash, token)
	}
}


/**
 * Creates a server session for the user and associates the session with a client cookie.
 * Assumes the user has already been authenticated.
 */
fun ApplicationCall.login(user: User) {

	val token = generateLoginToken(user.id)

	// create a new session
	sessions.set(User.Session.fromLogin(user, token.base62Encode()))
}

/**
 * Destroys the server session for the client and deletes the cookie
 */
fun ApplicationCall.logout() {

	// remove the login token from the database
	val session = sessions.get<User.Session>()
		?: return
	session.token?.let { revokeLoginToken(session.id, it.base62Decode()) }

	// remove the server session
	sessions.clear<User.Session>()
}


private const val HEADER_NEXTPYP_USERID = "nextpyp-userid"
private const val HEADER_NEXTPYP_TOKEN = "nextpyp-token"

/**
 * Find the authenticated user for this request, if any
 */
fun ApplicationCall.auth(): User? {

	// open endpoints don't require a authentication
	val path = request.path()
	if (AppPermission.Open.endpoints().any { pathMatchesEndpoint(path, it) }) {
		return null
	}

	// if either app header is present, try to authenticate as an app
	val userId = request.header(HEADER_NEXTPYP_USERID)
	val token = request.header(HEADER_NEXTPYP_TOKEN)
	return if (userId != null || token != null) {

		// need both though
		userId ?: throw AuthException("missing $HEADER_NEXTPYP_USERID header")
		token ?: throw AuthException("missing $HEADER_NEXTPYP_TOKEN header")

		authApp(userId, token)

	} else {
		// otherwise, authenticate using person rules
		authPerson()
	}
}

fun ApplicationCall.authOrThrow() =
	auth() ?: throw AuthException("Not authenticated, access denied")


/**
 * Authenticate the user using the auth type rules for people (ie using web browser clients)
 */
fun ApplicationCall.authPerson(): User? {

	// how to do auth?
	when (Config.instance.web.auth) {

		AuthType.None -> {

			// just use a dummy administrator
			val user = User(
				id = User.NoAuthId,
				name = User.NoAuthName,
				permissions = setOf(User.Permission.Admin),
				groups = emptySet(),
				haspw = false
			)

			// there's no explicit login process when auth is disabled,
			// so "log in" the dummy user now, so the UI doesn't think the user is logged out
			if (!isWebSocket()) {
				if (sessions.get<User.Session>() == null) {
					sessions.set(User.Session.fromUser(user))
				}
			}

			return user
		}

		AuthType.Login -> {

			// look for an existing server session
			val session = sessions.get<User.Session>()

			fun User.Session.auth(): User? {

				// try to get the session token
				val token: ByteArray? = token?.let {
					try {
						it.base62Decode()
					} catch (t: Throwable) {
						Backend.log.warn("corrupted login token: $it")
						null
					}
				}

				return if (verifyLoginToken(id, token)) {

					// we got a live one 'ere!
					Database.instance.users.getUser(id)

				} else {

					// remove any invalid sessions and tokens
					token?.let { revokeLoginToken(id, it) }
					if (!isWebSocket()) {
						sessions.clear<User.Session>()
					}

					null
				}
			}

			return if (Config.instance.web.demo) {

				fun getDemoUser(): User? =
					Database.instance.users.getUser(User.DemoId)
						?: run {
							Backend.log.warn("""
									|Demo mode active, but no demo user exists yet.
									|Create a user with id=${User.DemoId} and the Demo permission.
								""".trimMargin())
							return null
						}

				// demo mode: support usual logged-in users,
				// but if not logged in, use a generic demo user

				if (session != null) {

					// already have a session, authenticate it
					if (session.id == User.DemoId) {
						getDemoUser()
					} else {
						session.auth()
					}

				} else {

					// no existing session, log in as the demo user
					val demoUser = getDemoUser()
						?: return null
					if (!isWebSocket()) {
						sessions.set(User.Session.fromDemo(demoUser))
					}
					demoUser
				}

			} else {

				// not demo mode, everyone must have an account
				session?.auth()
			}
		}

		AuthType.ReverseProxy -> {

			// scan the request headers for the authentication credentials
			// The reverse proxy server should send us the "X-userid" header with the user id
			// NOTE: the reverse proxy should be configured to NOT forward this header from the client!
			val userId = request.header("X-userid")
				?: return null

			// get the user account, if any was configured
			val user = Database.instance.users.getUser(userId)
				?: return null

			// there's no explicit login process for pre-authed users
			// so just log them in now, if they're not logged in already
			if (!isWebSocket()) {
				if (sessions.get<User.Session>() == null) {
					sessions.set(User.Session.fromUser(user))
				}
			}

			return user
		}
	}
}


/**
 * Authenticate the user using rules for apps
 */
fun ApplicationCall.authApp(userId: String, token: String): User? {

	// validate the user and token
	var user = Database.instance.users.getUser(userId)
		?: return null
	val tokenInfo = AppTokenInfo.find(userId, token)
		?: return null

	// remove administrator access, unless explicitly granted by the token permissions
	if (AppPermission.Admin.appPermissionId !in tokenInfo.appPermissionIds) {
		user = user.withoutPermission(User.Permission.Admin)
	}

	val path = request.path()
	fun deny() =
		AuthException("access to endpoint denied")
			.withInternal("user $userId with valid app token for permissions ${tokenInfo.appPermissionIds} accessing endpoint $path")

	// check the endpoint permissions
	val tokenEndpoints = tokenInfo.appPermissionIds
		.map { AppPermissions[it] ?: throw deny() }
		.flatMap { it.endpoints() }
	val authorizedEndpoints = AppPermission.Open.endpoints() + tokenEndpoints
	if (authorizedEndpoints.none { pathMatchesEndpoint(path, it) }) {
		throw deny()
	}

	// authenticated and authorized!
	return user
}


private fun pathMatchesEndpoint(path: String, endpoint: String): Boolean {

	// endpoints will look like, eg:
	//   /kv/service
	//   /kv/service/func
	//   /service
	//   /service/func

	// paths will look the same, but path-component prefix matches are allowed
	// so path=/service/func should match both endpoint=/service and endpoint=/service/func
	// but raw string prefix matches are not
	// so path=/service/func should NOT match endpoint=/service/fun

	return path.toPath().startsWith(endpoint.toPath())
}


/**
 * Check if a web request handler is running from a web socket connection,
 * since HTTP response headers (like session cookies) can't be sent from a websocket handler
 */
private fun ApplicationCall.isWebSocket(): Boolean =
	// NOTE: origin.scheme is "http" even for websocket connections! (not "ws" like it's supposed to)
	//       so that's not a reliable signal =(
	//request.origin.scheme in listOf("ws", "wss")
	// let's use the uri path instead, since all the real-time service URL paths start with `/ws/`
	request.uri.startsWith("/ws/")


// unit tests for the path/endpoint matching logic
fun main() {

	assertEq(pathMatchesEndpoint("", "/"), false)
	assertEq(pathMatchesEndpoint("", "/service"), false)

	assertEq(pathMatchesEndpoint("service", "/service"), false)

	assertEq(pathMatchesEndpoint("/", "/"), true)

	assertEq(pathMatchesEndpoint("/service", "/"), true)
	assertEq(pathMatchesEndpoint("/service", "/service"), true)
	assertEq(pathMatchesEndpoint("/service", "/srv"), false)

	assertEq(pathMatchesEndpoint("/service/", "/"), true)
	assertEq(pathMatchesEndpoint("/service/", "/service"), true)
	assertEq(pathMatchesEndpoint("/service/", "/srv"), false)

	assertEq(pathMatchesEndpoint("/service/func", "/"), true)
	assertEq(pathMatchesEndpoint("/service/func", "/srv"), false)
	assertEq(pathMatchesEndpoint("/service/func", "/service/ep"), false)
	assertEq(pathMatchesEndpoint("/service/func", "/service/func"), true)
	assertEq(pathMatchesEndpoint("/service/func", "/service/fun"), false)
	assertEq(pathMatchesEndpoint("/service/func", "/service/function"), false)
	assertEq(pathMatchesEndpoint("/service/func", "/service"), true)

	assertEq(pathMatchesEndpoint("/service/func/", "/"), true)
	assertEq(pathMatchesEndpoint("/service/func/", "/srv"), false)
	assertEq(pathMatchesEndpoint("/service/func/", "/service/ep"), false)
	assertEq(pathMatchesEndpoint("/service/func/", "/service/func"), true)
	assertEq(pathMatchesEndpoint("/service/func/", "/service/fun"), false)
	assertEq(pathMatchesEndpoint("/service/func/", "/service/function"), false)
	assertEq(pathMatchesEndpoint("/service/func/", "/service"), true)
}
