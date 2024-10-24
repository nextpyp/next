package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.*
import edu.duke.bartesaghi.micromon.mongo.Database
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*


/**
 * make a route to handle user logins
 */
object AuthService {

	fun init(routing: Routing) {

		// NOTE: don't use KVision's RPC mechanism here, so we can capture passwords directly from the multipart form submission
		routing.route("auth") {

			post("bootstrap") handler@{

				when (Backend.config.web.auth) {

					// if there's no user auth, return a 404
					AuthType.None -> {
						call.respondText("fail",
							contentType = ContentType.Text.Plain,
							status = HttpStatusCode.NotFound
						)
					}

					AuthType.ReverseProxy -> {

						// make sure we actually need to bootstrap
						if (Database.instance.users.countUsers() > 0) {
							throw BadRequestException("can't bootstrap")
						}

						// make the user from the header
						val userId = call.request.header("X-userid")
							?: throw BadRequestException("No user information from reverse proxy")

						// read the username from the form post
						val multipart = call.receiveMultipart()
						val userName = (multipart.readPart() as? PartData.FormItem)
							?.value
							?: throw BadRequestException("malformed request")

						// create the new user
						Database.instance.users.create(
							User(
								id = userId,
								name = userName,
								permissions = setOf(User.Permission.Admin),
								groups = emptySet()
							)
						)

						// FTW!
						call.respondText("success",
							contentType = ContentType.Text.Plain
						)
					}

					AuthType.Login -> {

						// make sure we actually need to bootstrap
						if (Database.instance.users.countUsers() > 0) {
							throw BadRequestException("can't bootstrap")
						}

						// read the user from the form post
						val multipart = call.receiveMultipart()
						val userId = (multipart.readPart() as? PartData.FormItem)
							?.value
							?: throw BadRequestException("malformed request")
						val userName = (multipart.readPart() as? PartData.FormItem)
							?.value
							?: throw BadRequestException("malformed request")

						// create the new user
						val user = User(
							id = userId,
							name = userName,
							permissions = setOf(User.Permission.Admin),
							groups = emptySet()
						)
						Database.instance.users.create(user)

						// read the password, try to keep it in memory for as little as possible
						val passwordPart = (multipart.readPart() as? PartData.FileItem)
							?: throw BadRequestException("malformed request")
						Password(passwordPart.provider).use { password ->
							Database.instance.users.setPasswordHash(userId, password.hash())
						}

						// actually log in the new user
						call.login(user)

						// FTW!
						call.respondText("success",
							contentType = ContentType.Text.Plain
						)
					}
				}
			}

			post("login") handler@{

				// if there's no password auth, return a 404
				if (Backend.config.web.auth != AuthType.Login) {
					call.respondText("fail",
						contentType = ContentType.Text.Plain,
						status = HttpStatusCode.NotFound
					)
					return@handler
				}

				try {

					// read the username/password from the submission
					val multipart = call.receiveMultipart()
					val userId = (multipart.readPart() as? PartData.FormItem)
						?.value
						?: throw BadRequestException("malformed request")

					// lookup the user's hash
					val hash = Database.instance.users.getPasswordHash(userId)
						?: throw AuthException("authentication failed")

					// read the password and authenticate it
					// try to keep it in memory for as little as possible
					val passwordPart = (multipart.readPart() as? PartData.FileItem)
						?: throw BadRequestException("malformed request")
					val verified = Password(passwordPart.provider).use { password ->
						password.verify(hash)
					}
					if (!verified) {
						throw AuthException("authentication failed")
					}

					// all is well, log in the user
					val user = Database.instance.users.getUser(userId)
						?: throw RuntimeException("couldn't find authenticated user: $userId")
					call.login(user)

					// we did it!
					call.respondText("success",
						contentType = ContentType.Text.Plain
					)

				} catch (ex: AuthException) {

					// respond with HTTP 4o1 unauthorized
					call.respondText("fail",
						contentType = ContentType.Text.Plain,
						status = HttpStatusCode.Unauthorized
					)
				}
			}

			get("logout") handler@{

				// if there's no password auth, return a 404
				if (Backend.config.web.auth != AuthType.Login) {
					call.respondText("fail",
						contentType = ContentType.Text.Plain,
						status = HttpStatusCode.NotFound
					)
					return@handler
				}

				// easy peasy
				call.logout()

				call.respondText("success",
					contentType = ContentType.Text.Plain
				)
			}

			// allow logging in with a one-time link from an administrator
			get("token/{userId}/{encodedToken}") handler@{

				// if there's no password auth, return a 404
				if (Backend.config.web.auth != AuthType.Login) {
					call.respondText("Link logins disabled",
						contentType = ContentType.Text.Plain,
						status = HttpStatusCode.NotFound
					)
					return@handler
				}

				val userId = call.parameters.getOrFail("userId")
				val encodedToken = call.parameters.getOrFail("encodedToken")

				// authenticate the token
				val token = encodedToken.base62Decode()
				if (verifyLoginToken(userId, token)) {

					// consume this token (it's only one-time use)
					revokeLoginToken(userId, token)

					// login the user
					val user = Database.instance.users.getUser(userId)
						?: throw RuntimeException("couldn't find authenticated user: $userId")
					call.login(user)

					// yatta!!
					call.respondRedirect(
						permanent = false,
						url = "/#/account"
					)

				} else {

					Backend.log.info("Unsuccessful login token attempt for user $userId")

					// respond with HTTP 4o1 unauthorized
					call.respondText("Login was unsuccessful",
						contentType = ContentType.Text.Plain,
						status = HttpStatusCode.Unauthorized
					)
				}
			}

			post("setpw") handler@{

				// if there's no password auth, return a 404
				if (Backend.config.web.auth != AuthType.Login) {
					call.respondText("fail",
						contentType = ContentType.Text.Plain,
						status = HttpStatusCode.NotFound
					)
					return@handler
				}

				var user: User? = null
				try {

					// the user must be logged in
					user = call.authOrThrow()

					// don't allow Demo users to change their password
					if (user.isDemo) {
						throw AuthException("denied")
					}

					// lookup the user's hash, if any
					val hash = Database.instance.users.getPasswordHash(user.id)

					// read the passwords from the form
					// try to keep the passwords in memory for as little as possible
					val multipart = call.receiveMultipart()
					val oldpwPart = (multipart.readPart() as? PartData.FileItem)
						?: throw BadRequestException("malformed request")
					val newpwPart = (multipart.readPart() as? PartData.FileItem)
						?: throw BadRequestException("malformed request")

					// authenticate the old password if needed
					if (hash != null) {
						val verified = Password(oldpwPart.provider).use { password ->
							password.verify(hash)
						}
						if (!verified) {
							throw AuthException("authentication failed")
						}
					}

					// set the new password
					Password(newpwPart.provider).use { pw ->

						// enforce password restrictions
						if (pw.len() < Backend.config.web.minPasswordLength) {
							throw AuthException("New password must be at least ${Backend.config.web.minPasswordLength} characters long")
						}

						Database.instance.users.setPasswordHash(user.id, pw.hash())
					}

					// update the session cookie
					call.login(Database.instance.users.getUser(user.id)!!)

					// yatta!!
					call.respondText("success",
						contentType = ContentType.Text.Plain
					)

				} catch (ex: AuthException) {

					Backend.log.info("Unsuccessful password change attempt for user ${user?.id}, reason: ${ex.msg}")

					// respond with HTTP 4o1 unauthorized
					call.respondText(ex.message ?: "(unknown error)",
						contentType = ContentType.Text.Plain,
						status = HttpStatusCode.Unauthorized
					)
				}
			}
		}
	}
}