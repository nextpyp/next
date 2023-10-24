package edu.duke.bartesaghi.micromon.auth

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.mongo.Database
import kotlin.io.path.div


/** returns the user's name, or a default string if the user was not found */
fun User.Companion.lookupName(id: String, default: String = "???"): String =
	when {
		Backend.config.web.auth.hasUsers -> Database.users.getUser(id)?.name ?: default
		id == NoAuthId -> NoAuthName
		else -> default
	}


fun User.Companion.dir(userId: String) =
	Backend.config.web.sharedDir / "users" / userId

fun User.dir() =
	User.dir(id)
