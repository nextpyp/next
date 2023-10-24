package edu.duke.bartesaghi.micromon.mongo

import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.Group
import edu.duke.bartesaghi.micromon.User
import edu.duke.bartesaghi.micromon.toObjectId
import edu.duke.bartesaghi.micromon.toStringId
import org.bson.Document
import org.bson.conversions.Bson
import java.util.NoSuchElementException


class Users {

	/**
	 * The collection for users is called `permissions` for historical reasons.
	 * Feel free to change it to `users` if you think you won't break anyone's installation,
	 * or if you think you can convince them that creating all their users over again isn't annoying.
	 */
	private val collection = Database.db.getCollection("permissions")

	fun filter(userId: String) =
		Filters.eq("_id", userId)

	fun get(userId: String): Document? =
		collection.find(filter(userId)).useCursor { cursor ->
			cursor.firstOrNull()
		}

	fun write(userId: String, doccer: Document.() -> Unit) {
		collection.replaceOne(
			filter(userId),
			Document().apply { doccer() },
			ReplaceOptions().upsert(true)
		)
	}

	fun update(userId: String, vararg updates: Bson) {
		collection.updateOne(
			filter(userId),
			Updates.combine(updates.toList()),
			UpdateOptions().upsert(true)
		)
	}

	fun delete(userId: String) {
		collection.deleteOne(filter(userId))
	}

	fun countUsers(): Int =
		collection.countDocuments().toInt()

	fun getUser(userId: String): User? =
		get(userId)?.toUser()

	fun getAllUsers(): List<User> =
		collection.find().useCursor { cursor ->
			cursor
				.map { it.toUser() }
				.toList()
		}

	private fun Document.toUser() =
		User(
			id = getString("_id"),
			name = getString("name") ?: getString("_id"),
			permissions = (getListOfStrings("permissions") ?: emptyList())
				.mapNotNull { User.Permission[it] }
				.toSet(),
			groups = (getListOfStrings("groups") ?: emptyList())
				.mapNotNull { groupId -> Database.groups.get(groupId) }
				.toSet(),
			haspw = getString("pwhash") != null
		)

	fun create(user: User) {
		write(user.id) {
			set("name", user.name)
			set("permissions", user.permissions.map { it.id }.sorted())
			set("groups", user.groups.map { it.idOrThrow }.sorted())
		}
	}

	fun edit(user: User) {
		update(user.id,
			Updates.set("name", user.name),
			Updates.set("permissions", user.permissions.map { it.id }.sorted()),
			Updates.set("groups", user.groups.map { it.idOrThrow }.sorted())
		)
	}

	fun getPasswordHash(userId: String): String? =
		get(userId)?.getString("pwhash")

	fun setPasswordHash(userId: String, pwhash: String) {
		update(userId,
			Updates.set("pwhash", pwhash)
		)
	}

	fun removePasswordHash(userId: String) {
		update(userId,
			Updates.unset("pwhash")
		)
	}

	fun getTokenHash(userId: String): String? =
		get(userId)?.getString("tkhash")

	fun setTokenHash(userId: String, tkhash: String) {
		update(userId,
			Updates.set("tkhash", tkhash)
		)
	}

	fun removeTokenHash(userId: String) {
		update(userId,
			Updates.unset("tkhash")
		)
	}
}


class Groups {

	private val collection = Database.db.getCollection("groups")

	private fun filter(groupId: String) =
		Filters.eq("_id", groupId.toObjectId())

	/**
	 * Creates a new group that is a copy of the given group,
	 * except the id of the given group is ignored.
	 * The returned group has a new id.
	 */
	fun create(group: Group): Group {

		val result = collection.insertOne(
			Document().apply {
				set("name", group.name)
			}
		)

		val id = result.insertedId?.asObjectId()?.value?.toStringId()
			?: throw NoSuchElementException("inserted document had no id")

		return group.copy(id = id)
	}

	fun get(groupId: String): Group? =
		collection.find(filter(groupId)).useCursor { cursor ->
			cursor.firstOrNull()
		}
		?.toGroup()

	fun getOrThrow(groupId: String): Group =
		get(groupId)
			?: throw NoSuchElementException("no group found with id=$groupId")

	fun getAll(): List<Group> =
		collection.find().useCursor { cursor ->
			cursor
				.map { it.toGroup() }
				.toList()
		}

	fun edit(group: Group) {
		val groupId = group.id
			?: throw IllegalArgumentException("group must have an id")
		collection.updateOne(
			filter(groupId),
			Updates.combine(listOf(
				Updates.set("name", group.name)
			))
		)
	}

	fun delete(groupId: String) {
		collection.deleteOne(filter(groupId))
	}

	private fun Document.toGroup() =
		Group(
			id = getObjectId("_id")?.toStringId(),
			name = getString("name")
		)
}
