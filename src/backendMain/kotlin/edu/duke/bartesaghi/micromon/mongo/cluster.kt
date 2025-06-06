package edu.duke.bartesaghi.micromon.mongo

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.services.ClusterJobResultType
import edu.duke.bartesaghi.micromon.toObjectId
import edu.duke.bartesaghi.micromon.toStringId
import org.bson.Document
import org.bson.conversions.Bson
import java.util.NoSuchElementException


class ClusterJobs(db: MongoDatabase) {

	class Launches(db: MongoDatabase) {

		// NOTE: named "slurm___" for historical reasons
		private val collection = db.getCollection("slurmLaunch")

		init {
			// create indices to speed up common but slow operations
			// don't worry though, mongo will only actually create the index if it doesn't already exist
			collection.createIndex(Document().apply {
				this["owner"] = 1
			})
			collection.createIndex(Document().apply {
				this["name"] = 1
			})
		}

		fun clear() =
			collection.deleteMany(Document())

		fun filter(id: String) =
			Filters.eq("_id", id.toObjectId())

		fun get(id: String) =
			collection.find(filter(id)).useCursor { cursor ->
				cursor.firstOrNull()
			}

		fun find(
			ownerId: String? = null,
			clusterName: String? = null,
			notClusterName: List<String>? = null
		): List<Document> {
			val predicates = mutableListOf<Bson>()
			if (ownerId != null) {
				predicates.add(Filters.eq("owner", ownerId))
			}
			if (clusterName != null) {
				predicates.add(Filters.eq("clusterName", clusterName))
			}
			if (notClusterName != null) {
				predicates.add(Filters.nin("clusterName", notClusterName))
			}
			val filter = Filters.and(predicates)
			return collection.find(filter).useCursor { cursor ->
				cursor.toList()
			}
		}

		fun getByOwner(ownerId: String) =
			collection.find(Filters.eq("owner", ownerId)).useCursor { cursor ->
				cursor.toList()
			}

		/**
		 * Creates a new SLURM launch record and returns the id.
		 */
		fun create(doccer: Document.() -> Unit): String {

			val result = collection.insertOne(
				Document().apply { doccer() }
			)

			return result.insertedId?.asObjectId()?.toStringId()
				?: throw NoSuchElementException("inserted document had no id")
		}

		/**
		 * Appends to or overwrites part of a log document.
		 */
		fun update(id: String, vararg updates: Bson) =
			update(id, updates.toList())

		fun update(id: String, updates: List<Bson>) {
			collection.updateOne(
				filter(id),
				Updates.combine(updates)
			)
		}

		fun delete(id: String) {
			collection.deleteOne(filter(id))
		}
	}
	val launches = Launches(db)

	class Log(db: MongoDatabase) {

		// NOTE: named "slurm___" for historical reasons
		private val collection = db.getCollection("slurmLog")

		companion object {

			fun id(clusterJobId: String, arrayId: Int?): String =
				if (arrayId == null) {
					clusterJobId
				} else {
					"$clusterJobId/$arrayId"
				}

			fun parseId(id: String): Pair<String,Int?> =
				id.split("/").let { parts ->
					when (parts.size) {
						1 -> parts[0] to null
						2 -> parts[0] to (parts[1].toIntOrNull() ?: throw IllegalArgumentException("can't parse array id: ${parts[1]}"))
						else -> throw IllegalArgumentException("doesn't look like a log id: $id")
					}
				}
		}

		fun filter(clusterJobId: String, arrayId: Int? = null) =
			Filters.eq("_id", id(clusterJobId, arrayId))

		fun get(clusterJobId: String, arrayId: Int? = null) =
			collection.find(filter(clusterJobId, arrayId)).useCursor { cursor ->
				cursor.firstOrNull()
			}

		fun findArrayIdsByResultType(clusterJobId: String, resultType: ClusterJobResultType): List<Int> {
			val filter = Filters.and(
				Filters.regex("_id", "^$clusterJobId\\/"),
				// Log ids are either in the form `<clusterJobId>` or the form `<clusterJobId>/<arrayId>`.
				// Here, we want to match all logs with an array id, so we match the `<clusterJobId>/` prefix of the id.
				// NOTE: Sadly, we didn't have the foresight to add explicit clusterJobId and arrayId fields in log documents.
				//       So to match array jobs (and not their parent jobs) with a filter, we need to use a regex filter.
				//       Normally, a general regex filter would be pretty slow on a large collection like the cluster logs,
				//       but the Mongo docs assure us if we form the regex as a case-sensitive prefix match (eg /^foo/),
				//       then the DMBS can optimize that case with the index and give pretty good query performance.
				//       See: https://www.mongodb.com/docs/manual/reference/operator/query/regex/#index-use
				Filters.eq("result.type", resultType.id)
			)
			return collection.find(filter).useCursor { cursor ->
				cursor
					.mapNotNull { doc ->
						val (_, arrayId) = parseId(doc.getString("_id"))
						arrayId
					}
					.toList()
			}
		}

		/**
		 * Creates a new log record.
		 */
		fun create(clusterJobId: String, arrayId: Int? = null, doccer: Document.() -> Unit) {
			collection.insertOne(
				Document().apply {
					set("_id", id(clusterJobId, arrayId))
					doccer()
				}
			)
		}

		/**
		 * Appends to or overwrites part of a log document.
		 */
		fun update(clusterJobId: String, arrayId: Int?, vararg updates: Bson) =
			update(clusterJobId, arrayId, updates.toList())

		fun update(clusterJobId: String, arrayId: Int? = null, updates: List<Bson>) {
			collection.updateOne(
				filter(id(clusterJobId, arrayId)),
				Updates.combine(updates),
				UpdateOptions().upsert(true)
			)
		}

		fun delete(clusterJobId: String, arrayId: Int? = null) {
			collection.deleteOne(filter(clusterJobId, arrayId))
		}
	}
	val log = Log(db)
}
