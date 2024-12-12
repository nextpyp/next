package edu.duke.bartesaghi.micromon.mongo

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.bson.Document


class PypLog(db: MongoDatabase) {

	private val collection = db.getCollection("pypLog")

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["jobId"] = 1
		})
	}

	fun add(jobId: String, doccer: Document.() -> Unit) {
		collection.insertOne(
			Document().apply {
				set("jobId", jobId)
				doccer()
			}
		)
	}

	fun <R> getAll(jobId: String, block: (Sequence<Document>) -> R): R =
		collection.find(Filters.eq("jobId", jobId)).useCursor {
			block(it)
		}
}
