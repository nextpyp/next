package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.services.UserData
import kotlinx.serialization.Serializable


/**
 * Represents an authenticated user and their access permissions.
 *
 * Safe to send to the client.
 */
@Serializable
data class User(
	/** a unique identifier */
	val id: String,
	/** a display name */
	val name: String,
	val permissions: Set<Permission>,
	val groups: Set<Group>,
	val haspw: Boolean = false,
	val osUsername: String? = null
) {

	@Serializable
	enum class Permission(val id: String, val description: String) {

		Admin("admin", "Allows a user full access to everything."),
		EditSession("sessionEdit", "Allows a user to create and manage sessions."),
		Demo("demo", "Disables creating new projects.");

		companion object {

			operator fun get(id: String) =
				values().find { it.id == id }
		}
	}

	/** A token to associate a web session with a user */
	data class Session(
		val id: String,
		val name: String,
		val token: String?,
		val haspw: Boolean,
		val isdemo: Boolean
	) {

		companion object {

			fun fromUser(user: User) = Session(
				id = user.id,
				name = user.name,
				token = null,
				haspw = user.haspw,
				isdemo = false
			)

			fun fromLogin(user: User, token: String?) = Session(
				id = user.id,
				name = user.name,
				token = token,
				haspw = user.haspw,
				isdemo = false
			)

			fun fromDemo(user: User) = Session(
				id = user.id,
				name = user.name,
				token = null,
				haspw = user.haspw,
				isdemo = true
			)
		}
	}

	val isAdmin get() = hasPermission(Permission.Admin)

	/**
	 * Throws an exception if this user is not an admin,
	 * to prevent them from accessing restricted functions.
	 */
	fun adminOrThrow() {
		if (!isAdmin) {
			throw AuthException("access is restricted to administrators")
		}
	}

	val isDemo get() = hasPermission(Permission.Demo)

	fun notDemoOrThrow() {
		if (isDemo) {
			throw AuthException("not allowed for demo users")
		}
	}

	/**
	 * Returns true if the user has the permission set.
	 * Always returns true for admins.
	 * Returns false if the user is not an admin and doesn't have the permission set.
	 */
	fun hasPermission(perm: Permission) =
		perm in permissions

	/**
	 * Throws an exception if the user is not authorized for the permission
	 */
	fun authPermissionOrThrow(perm: Permission, allowAdmin: Boolean = true) {

		if (hasPermission(perm)) {
			return
		}
		if (allowAdmin && isAdmin) {
			return
		}
		
		throw AuthException("$perm permission denied").withInternal("user = $id")
	}

	fun withoutPermission(perm: Permission): User =
		copy(permissions = permissions.without(perm))

	fun hasGroup(group: Group) =
		hasGroup(group.idOrThrow)

	fun hasGroup(groupId: String) =
		groups.any { it.id == groupId }

	fun toData(): UserData =
		UserData(id, name)


	companion object {

		const val NoAuthId = "admin"
		const val NoAuthName = "Administrator"

		const val DemoId = "demo"
	}
}


/**
 * Authentication or authorization
 */
class AuthException(val msg: String) : RuntimeException(msg) {
	fun withInternal(internalMsg: String) =
		AuthExceptionWithInternal(msg, internalMsg)
}

class AuthExceptionWithInternal(val msg: String, internalMsg: String)
	: RuntimeException("$msg\nwith internal information: $internalMsg")
{
	fun external() = AuthException(msg)
}
