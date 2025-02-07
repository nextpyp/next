package edu.duke.bartesaghi.micromon.mongo

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.*
import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.files.*
import edu.duke.bartesaghi.micromon.pyp.ArgValues
import org.bson.Document
import org.bson.conversions.Bson
import java.time.Instant


/**
 * A database collection that holds data associated with data from another collection
 */
private class AssociatedCollection(db: MongoDatabase, collectionName: String) {

	val collection = db.getCollection(collectionName)

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["ownerId"] = 1
		})
	}

	fun filter(ownerId: String, dataId: String) =
		Filters.eq("_id", "$ownerId/$dataId")

	fun filterAll(ownerId: String) =
		Filters.eq("ownerId", ownerId)

	fun get(ownerId: String, dataId: String): Document? =
		collection.find(filter(ownerId, dataId)).useCursor {
			it.firstOrNull()
		}

	fun <R> getAll(ownerId: String, block: (Sequence<Document>) -> R): R =
		collection.find(filterAll(ownerId)).useCursor {
			block(it)
		}

	fun write(ownerId: String, dataId: String, doccer: Document.() -> Unit) {
		collection.replaceOne(
			filter(ownerId, dataId),
			Document().apply {
				this["ownerId"] = ownerId
				this["dataId"] = dataId
				this["timestamp"] = Instant.now().toEpochMilli()
				doccer()
			},
			ReplaceOptions().upsert(true)
		)
	}

	fun update(ownerId: String, dataId: String, vararg updates: Bson) {
		collection.updateOne(
			filter(ownerId, dataId),
			Updates.combine(listOf(
				Updates.set("timestamp", Instant.now().toEpochMilli()),
				Updates.set("ownerId", ownerId),
				Updates.set("dataId", dataId)
			) + updates),
			UpdateOptions().upsert(true)
		)
	}

	fun deleteAll(ownerId: String) {
		collection.deleteMany(filterAll(ownerId))
	}
}


class AvgRot(db: MongoDatabase, collectionName: String) {

	private val collection = AssociatedCollection(db, collectionName)

	companion object {
		const val key = "avgrot"
	}

	fun get(jobId: String, dataId: String): AVGROT? =
		collection.get(jobId, dataId)
			?.getDocument(key)
			?.readAVGROT()

	fun write(jobId: String, dataId: String, avgrot: AVGROT) {
		collection.write(jobId, dataId) {
			this[key] = avgrot.toDoc()
		}
	}

	fun deleteAll(jobId: String) =
		collection.deleteAll(jobId)
}

class DriftMetadata(db: MongoDatabase, collectionName: String) {

	private val collection = AssociatedCollection(db, collectionName)

	companion object {
		const val key = "driftMetadata"
	}

	fun get(ownerId: String, dataId: String): DMD? =
		collection.get(ownerId, dataId)
			?.getDocument(key)
			?.readDMD()

	fun countTilts(ownerId: String, dataId: String? = null): Long {

		val filter = if (dataId != null) {
			collection.filter(ownerId, dataId)
		} else {
			collection.filterAll(ownerId)
		}

		// do the aggregate query inside of the database engine, to try to get the best performance
		val results = collection.collection.aggregate(listOf(
			Aggregates.match(filter),
			Aggregates.project(Document().apply {
				this["driftMetadata.tilts"] = 1
			}),
			Aggregates.group(
				"numTilts",
				listOf(
					Accumulators.sum("numTilts", AggregationOperator.Array.size("driftMetadata.tilts"))
				)
			)
		))

		return results.firstOrNull()
			?.getInteger("numTilts")?.toLong()
			?: 0
	}

	fun write(ownerId: String, dataId: String, dmd: DMD) {
		collection.write(ownerId, dataId) {
			this[key] = dmd.toDoc()
		}
	}

	fun deleteAll(ownerId: String) =
		collection.deleteAll(ownerId)
}

class Parameters(db: MongoDatabase) {

	private val collection = db.getCollection("parameters")

	fun clear() =
		collection.deleteMany(Document())

	fun filter(ownerId: String) =
		Filters.eq("_id", ownerId)

	fun get(ownerId: String): Document? =
		collection.find(filter(ownerId)).useCursor {
			it.firstOrNull()
		}

	/**
	 * Creates or completely overwrites a micrograph document.
	 */
	fun write(ownerId: String, doccer: Document.() -> Unit) {
		collection.replaceOne(
			filter(ownerId),
			Document().apply { doccer() },
			ReplaceOptions().upsert(true)
		)
	}

	fun getParams(ownerId: String): ArgValues? =
		get(ownerId)
			?.getDocument("params")
			?.let { params ->
				ArgValues(Backend.instance.pypArgs).apply {
					for (key in params.keys) {
						val arg = args.arg(key)
							?: continue // old database data might refer to args that were deleted, so ignore missing args
						this[arg] = params[key]
					}
				}
			}

	fun writeParams(ownerId: String, params: ArgValues) =
		write(ownerId) {
			set("params", Document().apply {
				for (arg in params.args.args) {
					val value = params[arg]
						?: continue
					this[arg.fullId] = value
				}
			})
			set("timestamp", Instant.now().toEpochMilli())
		}

	fun delete(ownerId: String) {
		collection.deleteOne(filter(ownerId))
	}

	fun copy(srcOwnerId: String, dstOwnerId: String) {
		collection
			.find(filter(srcOwnerId))
			.useCursor { docs ->
				for (doc in docs) {
					collection.replaceOne(
						filter(dstOwnerId),
						doc.apply {
							set("_id", dstOwnerId)
						},
						ReplaceOptions().upsert(true)
					)
				}
			}
	}
}

class Micrographs(db: MongoDatabase) {

	private val collection = db.getCollection("micrographs")

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["jobId"] = 1
		})
	}

	fun clear() =
		collection.deleteMany(Document())

	fun filter(jobId: String, micrographId: String) =
		Filters.eq("_id", "$jobId/$micrographId")

	fun filterAll(jobId: String) =
		Filters.eq("jobId", jobId)

	fun get(jobId: String, micrographId: String): Document? =
		collection.find(filter(jobId, micrographId)).useCursor {
			it.firstOrNull()
		}

	fun <R> getAll(jobId: String, block: (Sequence<Document>) -> R): R =
		collection.find(filterAll(jobId)).useCursor {
			block(it)
		}

	suspend fun <R> getAllAsync(jobId: String, block: suspend (Sequence<Document>) -> R): R =
		collection.find(filterAll(jobId)).useCursor {
			block(it)
		}

	fun count(jobId: String): Long =
		collection.countDocuments(filterAll(jobId))

	data class Counts(
		val numMicrographs: Long,
		val numFrames: Long
	)

	fun counts(jobId: String): Counts {

		// do the aggregate query inside of the database engine, to try to get the best performance
		val results = collection.aggregate(listOf(
			Aggregates.match(filterAll(jobId)),
			Aggregates.project(Document().apply {
				this["xf"] = 1
			}),
			Aggregates.group(
				"counts",
				listOf(
					Accumulators.sum("numMicrographs", 1),
					Accumulators.sum("numFrames", AggregationOperator.Array.size("xf.samples"))
				)
			)
		))

		return results.firstOrNull()
			?.let { Counts(it.getInteger("numMicrographs").toLong(), it.getInteger("numFrames").toLong()) }
			?: Counts(0, 0)
	}

	/**
	 * Creates or completely overwrites a micrograph document.
	 */
	fun write(jobId: String, micrographId: String, doccer: Document.() -> Unit) {
		collection.replaceOne(
			filter(jobId, micrographId),
			Document().apply { doccer() },
			ReplaceOptions().upsert(true)
		)
	}

	/**
	 * Appends to or overwrites part of a micrograph document.
	 */
	fun update(jobId: String, micrographId: String, vararg updates: Bson) {
		collection.updateOne(
			filter(jobId, micrographId),
			Updates.combine(updates.toList())
		)
	}

	fun deleteAll(jobId: String) {
		collection.deleteMany(
			Filters.eq("jobId", jobId)
		)
	}
}


class PreprocessingFilters(db: MongoDatabase, val collectionName: String) {

	private val collection = db.getCollection(collectionName)

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["jobId"] = 1
		})
	}

	fun filter(jobId: String, name: String) =
		Filters.eq("_id", "$jobId/$name")

	fun get(jobId: String, name: String): Document? =
		collection.find(filter(jobId, name)).useCursor {
			it.firstOrNull()
		}

	fun <R> getAll(jobId: String, block: (Sequence<Document>) -> R): R =
		collection.find(Filters.eq("jobId", jobId)).useCursor {
			block(it)
		}

	/**
	 * Creates or completely overwrites a filter
	 */
	fun write(jobId: String, name: String, doccer: (Document) -> Unit) {
		collection.replaceOne(
			filter(jobId, name),
			Document().apply {
				set("jobId", jobId)
				doccer(this)
			},
			ReplaceOptions().upsert(true)
		)
	}

	fun delete(jobId: String, name: String) {
		collection.deleteOne(filter(jobId, name))
	}

	fun deleteAll(jobId: String) {
		collection.deleteMany(Filters.eq("jobId", jobId))
	}
}


/* NOTE: "zero-plurals" are annoying because the singluar and plural forms are the same word:
	https://www.merriam-webster.com/words-at-play/water-and-other-noncount-nouns
	But we really need separate words for those things in the code, because formal languages are strict like that.
	So let's just make an improper pluralization here. >8]
	Pretend it's Gollum talking or something.
*/
class TiltSerieses(db: MongoDatabase) {

	private val collection = db.getCollection("tiltSeries")

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["jobId"] = 1
		})
	}

	fun clear() =
		collection.deleteMany(Document())

	fun filter(jobId: String, tiltSeriesId: String) =
		Filters.eq("_id", "$jobId/$tiltSeriesId")

	fun filterAll(jobId: String) =
		Filters.eq("jobId", jobId)

	fun get(jobId: String, tiltSeriesId: String) =
		collection.find(filter(jobId, tiltSeriesId)).useCursor { cursor ->
			cursor.firstOrNull()
		}

	fun <R> getAll(jobId: String, block: (Sequence<Document>) -> R): R =
		collection.find(filterAll(jobId)).useCursor {
			block(it)
		}

	suspend fun <R> getAllAsync(jobId: String, block: suspend (Sequence<Document>) -> R): R =
		collection.find(filterAll(jobId)).useCursor {
			block(it)
		}

	fun count(jobId: String): Long =
		collection.countDocuments(filterAll(jobId))

	/**
	 * Creates or completely overwrites a micrograph document.
	 */
	fun write(jobId: String, tiltSeriesId: String, doccer: Document.() -> Unit) {
		collection.replaceOne(
			filter(jobId, tiltSeriesId),
			Document().apply { doccer() },
			ReplaceOptions().upsert(true)
		)
	}

	/**
	 * Appends to or overwrites part of a tilt series document.
	 */
	fun update(jobId: String, tiltSeriesId: String, vararg updates: Bson) {
		collection.updateOne(
			filter(jobId, tiltSeriesId),
			Updates.combine(updates.toList())
		)
	}

	fun deleteAll(jobId: String) {
		collection.deleteMany(
			Filters.eq("jobId", jobId)
		)
	}

	fun copyAll(srcJobId: String, dstJobId: String) {
		collection
			.find(filterAll(srcJobId))
			.useCursor { docs ->
				for (doc in docs) {
					val tiltSeriesId = doc.getString("tiltSeriesId")
					collection.replaceOne(
						filter(dstJobId, tiltSeriesId),
						doc.apply {
							set("_id", "$dstJobId/$tiltSeriesId")
							set("jobId", dstJobId)
						},
						ReplaceOptions().upsert(true)
					)
				}
			}
	}
}

class Reconstructions(db: MongoDatabase) {

	private val collection = db.getCollection("reconstructions")

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["jobId"] = 1
		})
	}

	fun clear() =
		collection.deleteMany(Document())

	fun filter(jobId: String, reconstructionId: String) =
		Filters.eq("_id", "$jobId/$reconstructionId")

	fun get(jobId: String, reconstructionId: String) =
		collection.find(filter(jobId, reconstructionId)).useCursor { cursor ->
			cursor.firstOrNull()
		}

	fun <R> getAll(jobId: String, block: (Sequence<Document>) -> R): R =
		collection.find(Filters.eq("jobId", jobId)).useCursor {
			block(it)
		}

	/**
	 * Creates or completely overwrites a reconstruction document.
	 */
	fun write(jobId: String, reconstructionId: String, doccer: Document.() -> Unit) {
		collection.replaceOne(
			filter(jobId, reconstructionId),
			Document().apply { doccer() },
			ReplaceOptions().upsert(true)
		)
	}

	/**
	 * Appends to or overwrites part of a reconstruction document.
	 */
	fun update(jobId: String, reconstructionId: String, vararg updates: Bson) {
		collection.updateOne(
			filter(jobId, reconstructionId),
			Updates.combine(updates.toList())
		)
	}

	fun deleteAll(jobId: String) {
		collection.deleteMany(
			Filters.eq("jobId", jobId)
		)
	}
}


class DrgnMaps(db: MongoDatabase) {

	private val collection = db.getCollection("drgnMaps")

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["jobId"] = 1
		})
	}

	fun clear() =
		collection.deleteMany(Document())

	fun filter(jobId: String, drgnMapId: String) =
		Filters.eq("_id", "$jobId/$drgnMapId")

	fun get(jobId: String, drgnMapId: String) =
		collection.find(filter(jobId, drgnMapId)).useCursor { cursor ->
			cursor.firstOrNull()
		}

	fun <R> getAll(jobId: String, block: (Sequence<Document>) -> R): R =
		collection.find(Filters.eq("jobId", jobId)).useCursor {
			block(it)
		}

	/**
	 * Creates or completely overwrites a map document.
	 */
	fun write(jobId: String, drgnMapId: String, doccer: Document.() -> Unit) {
		collection.replaceOne(
			filter(jobId, drgnMapId),
			Document().apply { doccer() },
			ReplaceOptions().upsert(true)
		)
	}

	/**
	 * Appends to or overwrites part of a map document.
	 */
	fun update(jobId: String, drgnMapId: String, vararg updates: Bson) {
		collection.updateOne(
			filter(jobId, drgnMapId),
			Updates.combine(updates.toList())
		)
	}

	fun deleteAll(jobId: String) {
		collection.deleteMany(
			Filters.eq("jobId", jobId)
		)
	}
}

class Refinements(db: MongoDatabase) {

	private val collection = db.getCollection("refinements")

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["jobId"] = 1
		})
	}

	fun clear() =
		collection.deleteMany(Document())

	fun filter(jobId: String, dataId: String) =
		Filters.eq("_id", "$jobId/$dataId")

	fun get(jobId: String, dataId: String) =
		collection.find(filter(jobId, dataId)).useCursor { cursor ->
			cursor.firstOrNull()
		}

	fun <R> getAll(jobId: String, block: (Sequence<Document>) -> R): R =
		collection.find(Filters.eq("jobId", jobId)).useCursor {
			block(it)
		}

	/**
	* Creates or completely overwrites a refinement document.
	*/
	fun write(jobId: String, dataId: String, doccer: Document.() -> Unit) {
		collection.replaceOne(
			filter(jobId, dataId),
			Document().apply { doccer() },
			ReplaceOptions().upsert(true)
		)
	}

	/**
	* Appends to or overwrites part of a refinement document.
	*/
	fun update(jobId: String, dataId: String, vararg updates: Bson) {
		collection.updateOne(
			filter(jobId, dataId),
			Updates.combine(updates.toList())
		)
	}

	fun deleteAll(jobId: String) {
		collection.deleteMany(
			Filters.eq("jobId", jobId)
		)
	}

	fun deleteAllNotIteration(jobId: String, iteration: Int) {
		collection.deleteMany(
			Filters.and(
				Filters.eq("jobId", jobId),
				Filters.ne("iteration", iteration)
			)
		)
	}
}

class RefinementBundles(db: MongoDatabase) {

	private val collection = db.getCollection("refinementBundles")

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["jobId"] = 1
		})
	}

	fun clear() =
		collection.deleteMany(Document())

	fun filter(jobId: String, refinementBundleId: String) =
		Filters.eq("_id", "$jobId/$refinementBundleId")

	fun get(jobId: String, refinementBundleId: String) =
		collection.find(filter(jobId, refinementBundleId)).useCursor { cursor ->
			cursor.firstOrNull()
		}

	fun <R> getAll(jobId: String, block: (Sequence<Document>) -> R): R =
		collection.find(Filters.eq("jobId", jobId)).useCursor {
			block(it)
		}

	/**
	* Creates or completely overwrites a refinement bundle document.
	*/
	fun write(jobId: String, refinementBundleId: String, doccer: Document.() -> Unit) {
		collection.replaceOne(
			filter(jobId, refinementBundleId),
			Document().apply { doccer() },
			ReplaceOptions().upsert(true)
		)
	}

	/**
	* Appends to or overwrites part of a refinement bundle document.
	*/
	fun update(jobId: String, refinementBundleId: String, vararg updates: Bson) {
		collection.updateOne(
			filter(jobId, refinementBundleId),
			Updates.combine(updates.toList())
		)
	}

	fun deleteAll(jobId: String) {
		collection.deleteMany(
			Filters.eq("jobId", jobId)
		)
	}

	fun deleteAllNotIteration(jobId: String, iteration: Int) {
		collection.deleteMany(
			Filters.and(
				Filters.eq("jobId", jobId),
				Filters.ne("iteration", iteration)
			)
		)
	}
}

class TwoDClasses(db: MongoDatabase) {

	private val collection = db.getCollection("twoDClasses")

	// NOTE: "owner" here can mean sessions as well as jobs

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["ownerId"] = 1
		})
	}

	fun clear() =
		collection.deleteMany(Document())

	fun filter(ownerId: String, classesId: String) =
		Filters.eq("_id", "$ownerId/$classesId")

	fun filterAll(ownerId: String) =
		Filters.eq("ownerId", ownerId)

	fun get(ownerId: String, classesId: String) =
		collection.find(filter(ownerId, classesId)).useCursor { cursor ->
			cursor.firstOrNull()
		}

	fun <R> getAll(ownerId: String, block: (Sequence<Document>) -> R): R =
		collection.find(Filters.eq("ownerId", ownerId)).useCursor {
			block(it)
		}

	/**
	 * Creates or completely overwrites a classes document.
	 */
	fun write(ownerId: String, twoDClassesId: String, doc: Document) {
		collection.replaceOne(
			filter(ownerId, twoDClassesId),
			doc,
			ReplaceOptions().upsert(true)
		)
	}

	/**
	 * Appends to or overwrites part of a classes document.
	 */
	fun update(ownerId: String, twoDClassesId: String, vararg updates: Bson) {
		collection.updateOne(
			filter(ownerId, twoDClassesId),
			Updates.combine(updates.toList())
		)
	}

	fun deleteAll(ownerId: String) {
		collection.deleteMany(filterAll(ownerId))
	}
}


class TiltExclusions(db: MongoDatabase) {

	private val collection = db.getCollection("tiltExclusions")

	private fun filter(jobId: String) =
		Filters.eq("_id", jobId)

	private fun get(jobId: String): Document? =
		collection.find(filter(jobId)).useCursor {
			it.firstOrNull()
		}

	fun delete(jobId: String) {
		collection.deleteOne(filter(jobId))
	}

	private fun update(jobId: String, vararg updates: Bson) {
		collection.updateOne(
			filter(jobId),
			Updates.combine(updates.toList()),
			UpdateOptions().upsert(true)
		)
	}

	private fun Int.indexToKey(): String =
		"i$this"
	private fun String.keyToIndex(): Int =
		substring(1).toInt()

	fun getForJob(jobId: String): Map<String,Map<Int,Boolean>>? =
		get(jobId)?.let { doc ->
			doc.keys
				.filter { it != "_id" }
				.associate { keyStr ->
					val tiltSeriesId = keyStr.fromMongoSafeKey()
					val filter = doc.getMap<Boolean>(keyStr)
						.mapKeys { (key, _) -> key.keyToIndex() }
					tiltSeriesId to filter
				}
		}

	fun getForTiltSeries(jobId: String, tiltSeriesId: String): Map<Int,Boolean>? =
		get(jobId)
			?.getMap<Boolean>(tiltSeriesId.toMongoSafeKey())
			?.mapKeys { (key, _) -> key.keyToIndex() }

	fun setForTilt(jobId: String, tiltSeriesId: String, tiltIndex: Int, value: Boolean) =
		update(
			jobId,
			"${tiltSeriesId.toMongoSafeKey()}.${tiltIndex.indexToKey()}".let { key ->
				if (value) {
					Updates.set(key, value)
				} else {
					Updates.unset(key)
				}
			}
		)
}
