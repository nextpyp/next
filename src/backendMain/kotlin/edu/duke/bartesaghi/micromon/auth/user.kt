package edu.duke.bartesaghi.micromon.auth

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.mongo.Database
import io.kvision.remote.ServiceException
import kotlin.io.path.div


/** returns the user's name, or a default string if the user was not found */
fun User.Companion.lookupName(id: String, default: String = "???"): String =
	when {
		Backend.config.web.auth.hasUsers -> Database.users.getUser(id)?.name ?: default
		id == NoAuthId -> NoAuthName
		else -> default
	}


fun User.Companion.dir(userId: String, osUsername: String?) =
	if (osUsername != null) {
		Backend.config.web.sharedDir / "os-users" / osUsername
	} else {
		Backend.config.web.sharedDir / "users" / userId
	}

fun User.dir() =
	User.dir(id, osUsername)


fun User.authUserOrThrow(userId: String): User {

	// users are authorized for themselves
	if (id == userId) {
		return this
	}

	// and admins are too
	if (isAdmin) {
		return Database.users.getUser(userId)
			?: throw ServiceException("user not found")
	}

	// but no one else
	throw AuthException("access to other user denied")
		.withInternal("access to $userId requested by user $id")
}
