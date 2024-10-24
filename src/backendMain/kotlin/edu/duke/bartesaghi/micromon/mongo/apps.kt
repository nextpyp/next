package edu.duke.bartesaghi.micromon.mongo

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import edu.duke.bartesaghi.micromon.auth.AppTokenInfo
import edu.duke.bartesaghi.micromon.auth.AppTokenRequest
import edu.duke.bartesaghi.micromon.toObjectId
import org.bson.Document


class AppTokens(db: MongoDatabase) {

	private val collection = db.getCollection("appTokens")

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["userId"] = 1
		})
	}

	private fun filterAll(userId: String) =
		Filters.eq("userId", userId)

	private fun filter(tokenId: String) =
		Filters.eq("_id", tokenId.toObjectId())

	fun getAll(userId: String): List<AppTokenInfo> =
		collection.find(filterAll(userId)).useCursor { cursor ->
			cursor
				.map { AppTokenInfo.fromDoc(it) }
				.toList()
		}

	fun get(tokenId: String): AppTokenInfo? =
		collection.find(filter(tokenId)).useCursor { cursor ->
			cursor
				.firstOrNull()
				?.let { AppTokenInfo.fromDoc(it) }
		}

	fun find(userId: String, verifier: (hash: String) -> Boolean): AppTokenInfo? =
		collection.find(filterAll(userId)).useCursor { cursor ->
			cursor
				.map { AppTokenInfo.fromDoc(it) }
				.filter { token -> verifier(token.hash) }
				.firstOrNull()
		}

	fun create(creator: (tokenId: String) -> AppTokenInfo): AppTokenInfo =
		collection.create { tokenId ->
			val info = creator(tokenId)
			info to info.toDoc()
		}

	fun delete(info: AppTokenInfo) {
		collection.deleteOne(filter(info.tokenId))
	}

	fun deleteAll(userId: String) {
		collection.deleteMany(filterAll(userId))
	}
}



class AppTokenRequests(db: MongoDatabase) {

	private val collection = db.getCollection("appTokenRequests")

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["userId"] = 1
		})
	}

	private fun filterAll(userId: String) =
		Filters.eq("userId", userId)

	private fun filter(requestId: String) =
		Filters.eq("_id", requestId.toObjectId())

	fun getAll(userId: String): List<AppTokenRequest> =
		collection.find(filterAll(userId)).useCursor { cursor ->
			cursor
				.map { AppTokenRequest.fromDoc(it) }
				.toList()
		}

	fun get(requestId: String): AppTokenRequest? =
		collection.find(filter(requestId)).useCursor { cursor ->
			cursor
				.firstOrNull()
				?.let { AppTokenRequest.fromDoc(it) }
		}

	fun create(creator: (requestId: String) -> AppTokenRequest): AppTokenRequest =
		collection.create { requestId ->
			val request = creator(requestId)
			request to request.toDoc()
		}

	fun delete(info: AppTokenRequest) {
		collection.deleteOne(filter(info.requestId))
	}

	fun deleteAll(userId: String) {
		collection.deleteMany(filterAll(userId))
	}
}
