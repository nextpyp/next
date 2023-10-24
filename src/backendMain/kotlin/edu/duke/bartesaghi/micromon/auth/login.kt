package edu.duke.bartesaghi.micromon.auth

import de.mkammerer.argon2.Argon2Factory
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.services.AuthType
import io.ktor.application.ApplicationCall
import io.ktor.request.header
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


fun generateLoginToken(userId: String): ByteArray {

	// generate the login token
	// since these tokens are not real passwords and can never protect other resources (like a users bank account),
	// we don't need to take any in-memory precautions to protect these tokens from server administrators
	val token = ByteArray(64)
	SecureRandom().nextBytes(token)

	// hash it
	// login tokens are chosen by the server and therefore make very strong passwords
	// therefore, even "weak" hashes are sufficient to protect them
	// we don't even need to add any extra salt
	// https://security.stackexchange.com/questions/63435/why-use-an-authentication-token-instead-of-the-username-password-per-request/63438#63438
	val tkhash = argonForTokens.hash(
		1, // iterations
		128, // memory, in KiB
		1, // threads
		token
	)

	// save the login token in the database
	Database.users.setTokenHash(userId, tkhash)

	return token
}

fun verifyLoginToken(userId: String, token: ByteArray?): Boolean {

	token ?: return false

	val tkhash = Database.users.getTokenHash(userId)
		?: return false

	return argonForTokens.verify(tkhash, token)
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

	val session = sessions.get<User.Session>() ?: return

	// remove the server session
	sessions.clear<User.Session>()

	// remove the login token from the database
	Database.users.removeTokenHash(session.id)
}


/**
 * Find the authenticated user for this request, if any
 */
fun ApplicationCall.auth(): User? {

	// how to do auth?
	when (Backend.config.web.auth) {

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
			if (sessions.get<User.Session>() == null) {
				sessions.set(User.Session.fromUser(user))
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
					Database.users.getUser(id)

				} else {

					// remove any invalid sessions and tokens
					sessions.clear<User.Session>()
					Database.users.removeTokenHash(id)

					null
				}
			}

			return if (Backend.config.web.demo) {

				fun getDemoUser(): User? =
					Database.users.getUser(User.DemoId)
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
					sessions.set(User.Session.fromDemo(demoUser))
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
			val user = Database.users.getUser(userId)
				?: return null

			// there's no explicit login process for pre-authed users
			// so just log them in now, if they're not logged in already
			if (sessions.get<User.Session>() == null) {
				sessions.set(User.Session.fromUser(user))
			}

			return user
		}
	}
}

fun ApplicationCall.authOrThrow() =
	auth() ?: throw AuthenticationException("Not authenticated, access denied")
