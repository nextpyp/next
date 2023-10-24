package edu.duke.bartesaghi.micromon.mongo

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.SessionData
import org.bson.Document
import org.bson.conversions.Bson
import java.util.NoSuchElementException


object SessionNumberLock

class Sessions {

	private val collection = Database.db.getCollection("sessions")

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["type"] = 1
		})
	}

	fun filter(sessionId: String) =
		Filters.eq("_id", sessionId.toObjectId())

	fun get(sessionId: String) =
		collection.find(filter(sessionId)).useCursor { cursor ->
			cursor.firstOrNull()
		}

	fun <R> getAll(block: (Sequence<Document>) -> R): R =
		collection.find().useCursor { cursor ->
			block(cursor)
		}

	data class Groups(
		val sessionId: String,
		val finishedGroupId: String?,
		val nextGroupId: String?
	) {

		val newestGroupId: String? get() =
			nextGroupId ?: finishedGroupId
	}

	fun <R> getGroupsByType(type: String, block: (Sequence<Groups>) -> R): R =
		collection.find(Filters.eq("type", type))
			.projection(Projections.include("finishedArgs.groupId", "nextArgs.groupId"))
			// NOTE: `_id` is included in the projection unless we explicitly exclude it
			.useCursor { docs ->
				block(docs.map { doc ->
					val finishedArgs: Document? = doc.getDocument("finishedArgs")
					val nextArgs: Document? = doc.getDocument("nextArgs")
					Groups(
						sessionId = doc.getObjectId("_id").toStringId(),
						finishedGroupId = finishedArgs?.getString("groupId"),
						nextGroupId = nextArgs?.getString("groupId")
					)
				})
			}

	data class NamesAndGroups(
		val sessionId: String,
		val sessionNumber: Int?,
		val finishedName: String?,
		val finishedGroupId: String?,
		val nextName: String?,
		val nextGroupId: String?
	) {

		val nextNumberedName: String? get() =
			nextName?.let { SessionData.numberedName(sessionNumber, it) }

		val finishedNumberedName: String? get() =
			finishedName?.let { SessionData.numberedName(sessionNumber, it) }

		val newestNumberedName: String? get() =
			nextNumberedName ?: finishedNumberedName

		val newestGroupId: String? get() =
			nextGroupId ?: finishedGroupId
	}

	fun <R> getNamesAndGroupsByType(type: String, block: (Sequence<NamesAndGroups>) -> R): R =
		collection.find(Filters.eq("type", type))
			.projection(Projections.include("sessionNumber", "finishedArgs.name", "finishedArgs.groupId", "nextArgs.name", "nextArgs.groupId"))
			// NOTE: `_id` is included in the projection unless we explicitly exclude it
			.useCursor { docs ->
				block(docs.map { doc ->
					val finishedArgs: Document? = doc.getDocument("finishedArgs")
					val nextArgs: Document? = doc.getDocument("nextArgs")
					NamesAndGroups(
						sessionId = doc.getObjectId("_id").toStringId(),
						sessionNumber = doc.getInteger("sessionNumber"),
						finishedName = finishedArgs?.getString("name"),
						finishedGroupId = finishedArgs?.getString("groupId"),
						nextName = nextArgs?.getString("name"),
						nextGroupId = nextArgs?.getString("groupId")
					)
				})
			}

	/**
	 * Creates a new session and returns the id and number.
	 */
	fun create(doccer: Document.() -> Unit): Pair<String,Int> {

		// assign the session an auto-incrementing number
		synchronized(SessionNumberLock) {
			// NOTE: synchronize all session creations so we don't get data races if anything weird happens,
			//       like a user creating two sessions at the exact same time somehow
			// NOTE: we could use database transactions to accomplish the same goal here, but this is
			//       much much simpler and the marginally higher performance of transactions is totally unnecessary

			// get an auto-incrementing number for the session
			val sessionNumber = getAll { docs ->
				docs
					.maxOfOrNull { it.getInteger("sessionNumber") ?: 0 }
					?.let { highestSessionNumber -> highestSessionNumber + 1 }
					?: 1
			}

			val result = collection.insertOne(
				Document().apply {
					doccer()
					set("sessionNumber", sessionNumber)
				}
			)

			val sessionId = result.insertedId?.asObjectId()?.value?.toStringId()
				?: throw NoSuchElementException("inserted document had no id")

			return sessionId to sessionNumber
		}
	}

	fun update(sessionId: String, vararg updates: Bson) =
		update(sessionId, updates.toList())

	fun update(sessionId: String, updates: List<Bson>) {
		collection.updateOne(
			filter(sessionId),
			Updates.combine(updates)
		)
	}

	fun delete(sessionId: String) {
		collection.deleteOne(filter(sessionId))
	}
}


class SessionExports {

	private val collection = Database.db.getCollection("sessionExports")

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["sessionId"] = 1
		})
	}

	fun filter(exportId: String) =
		Filters.eq("_id", exportId.toObjectId())

	fun get(exportId: String) =
		collection.find(filter(exportId)).useCursor { cursor ->
			cursor.firstOrNull()
		}

	fun <R> getAll(
		sessionId: String,
		block: (Sequence<Document>) -> R
	): R =
		collection.find(Filters.eq("sessionId", sessionId))
			.useCursor(block)

	/**
	 * Creates a new session export and returns the id.
	 */
	fun create(doccer: Document.() -> Unit): String {

		val result = collection.insertOne(
			Document().apply { doccer() }
		)

		return result.insertedId?.asObjectId()?.value?.toStringId()
			?: throw NoSuchElementException("inserted document had no id")
	}

	fun update(exportId: String, vararg updates: Bson) =
		update(exportId, updates.toList())

	fun update(exportId: String, updates: List<Bson>) {
		collection.updateOne(
			filter(exportId),
			Updates.combine(updates)
		)
	}

	fun delete(exportId: String) {
		collection.deleteOne(filter(exportId))
	}
}
