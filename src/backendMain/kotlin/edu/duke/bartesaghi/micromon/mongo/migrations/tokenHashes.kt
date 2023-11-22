package edu.duke.bartesaghi.micromon.mongo.migrations

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.useCursor
import org.slf4j.Logger


/**
 * Migrate the single token hash to a list of token hashes
 */
fun migrationTokenHashes(database: Database, log: Logger) {

	val users = database.db.getCollection("permissions")

	val tkhashesByUserId = users.find().useCursor { cursor ->
		cursor
			.mapNotNull f@{ doc ->
				val hash = doc.getString("tkhash")
					?: return@f null
				val userId = doc.getString("_id")
				userId to hash
			}
			.toList()
	}

	for ((userId, tkhash) in tkhashesByUserId) {
		users.updateOne(
			Filters.eq("_id", userId),
			Updates.combine(listOf(
				Updates.push("tkhashes", tkhash)
			))
		)
	}

	log.info("Migrated token hashes for ${tkhashesByUserId.size} users successfully.")
}
