package edu.duke.bartesaghi.micromon.mongo

import com.mongodb.MongoTimeoutException
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.changestream.OperationType
import edu.duke.bartesaghi.micromon.toStringId
import org.bson.Document
import org.slf4j.LoggerFactory


class WatcherThread(collection: MongoCollection<Document>, handler: (Report) -> Unit) {

	sealed class Report {

		abstract val id: String

		data class Insert(
			override val id: String
		) : Report()

		data class Update(
			override val id: String,
			val keys: Set<String>
		) : Report()
	}

	private val thread = Thread {

		// get a watcher for the database collection
		// since this keeps a database connection open,
		// the call might timeout if the database is not immediately available
		// if that happens, don't fret. just keep trying until the database responds
		while (true) {
			try {
				for (changeDoc in collection.watch()) {

					// get the id of the changed document, if the document even has an id
					val id = changeDoc.documentKey!!["_id"] ?: continue
					val idstr = when {
						id.isString -> id.asString().value
						id.isObjectId -> id.asObjectId().toStringId()
						else -> throw IllegalArgumentException("unrecognized id type: $id")
					}

					when (changeDoc.operationType) {

						OperationType.INSERT,
						OperationType.REPLACE -> {
							handler(Report.Insert(idstr))
						}

						OperationType.UPDATE -> {
							val keys = changeDoc.updateDescription!!.updatedFields!!.keys
							handler(Report.Update(idstr, keys))
						}

						// ignore other update types
						else -> Unit
					}
				}

			} catch (t: MongoTimeoutException) {

				// timeout, try again
				LoggerFactory.getLogger(javaClass).warn("database timeout, retrying")

			} catch (t: Throwable) {

				// other error, report it
				LoggerFactory.getLogger(javaClass).error("database watcher error", t)
			}
		}
	}

	init {
		thread.apply {
			name = "MongoWatcher: ${collection.namespace}"
			isDaemon = true
			start()
		}
	}
}
