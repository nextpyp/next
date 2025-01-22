package edu.duke.bartesaghi.micromon.auth

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.mongo.Database
import io.kvision.remote.ServiceException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.div


/** returns the user's name, or a default string if the user was not found */
fun User.Companion.lookupName(id: String, default: String = "???"): String =
	when {
		Config.instance.web.auth.hasUsers -> Database.instance.users.getUser(id)?.name ?: default
		id == NoAuthId -> NoAuthName
		else -> default
	}


fun User.Companion.dir(userId: String, osUsername: String?) =
	if (osUsername != null) {
		Config.instance.web.sharedDir / "os-users" / osUsername
	} else {
		Config.instance.web.sharedDir / "users" / userId
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
		return Database.instance.users.getUser(userId)
			?: throw ServiceException("user not found")
	}

	// but no one else
	throw AuthException("access to other user denied")
		.withInternal("access to $userId requested by user $id")
}


fun User.allowedRoots(): List<Path> {

	val out = ArrayList<Path>()

	// allow all binds configured by the administrator
	out.addAll(Config.instance.pyp.binds)

	// allow the user's folder
	out.add(dir())

	return out
}

fun Path.allowedByOrThrow(user: User, extraAllowedRoots: List<Path> = emptyList()): Path {

	fun bail(): Nothing =
		throw AuthExceptionWithInternal("folder not allowed", "for user $user to path $this")

	// don't allow any relative paths like ../../../ etc
	if (contains(Paths.get(".."))) {
		bail()
	}

	// otherwise, the path must be in one of the allowed roots
	val allowedRoots = user.allowedRoots() + extraAllowedRoots
	if (allowedRoots.none { startsWith(it) }) {
		bail()
	}

	return this
}

fun String.allowedPathOrThrow(user: User, extraAllowedRoots: List<Path> = emptyList()): Path =
	Paths.get(this)
		.allowedByOrThrow(user, extraAllowedRoots)
