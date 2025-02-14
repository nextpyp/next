package edu.duke.bartesaghi.micromon.mongo

import com.mongodb.ConnectionString
import com.mongodb.DuplicateKeyException
import com.mongodb.MongoClientSettings
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import edu.duke.bartesaghi.micromon.base62Decode
import edu.duke.bartesaghi.micromon.base62Encode
import edu.duke.bartesaghi.micromon.mongo.migrations.Migrations
import edu.duke.bartesaghi.micromon.toObjectId
import edu.duke.bartesaghi.micromon.toStringId
import org.bson.Document
import java.util.concurrent.TimeUnit


object Database {

	private const val HOST = "localhost"
	private const val PORT = 27017

	private var _instance: DatabaseConnection? = null

	val instance: DatabaseConnection get() =
		_instance ?: connect("mongodb://$HOST:$PORT")

	fun connect(connectionString: String): DatabaseConnection {

		disconnect()

		val instance = DatabaseConnection(MongoClients.create(
			MongoClientSettings.builder()
				// set a shorter timeout, so we can retry the connection faster
				.applyToClusterSettings { settings ->
					settings.serverSelectionTimeout(5, TimeUnit.SECONDS)
				}
				.applyToSocketSettings { settings ->
					settings.readTimeout(5, TimeUnit.SECONDS)
					settings.connectTimeout(5, TimeUnit.SECONDS)
				}
				.applyConnectionString(ConnectionString(connectionString))
				.build()
		))
		_instance = instance
		return instance
	}

	fun disconnect() {
		_instance?.close()
		_instance = null
	}

	fun init() {
		// init the default database connection
		instance
	}
}


class DatabaseConnection(val client: MongoClient) : AutoCloseable {

	companion object {
		private const val NAME = "micromon"
	}

	fun <R> transaction(block: () -> R): R =
		client.startSession().use { session ->
			session.startTransaction()
			try {
				val result = block()
				session.commitTransaction()
				result
			} catch (t: Throwable) {
				session.abortTransaction()
				throw t
			}
		}

	/** returns true iff this is the first time we're accessing the database and it needs to be intialized */
	val isNew = NAME !in client.listDatabaseNames()
	val db = client.getDatabase(NAME)

	override fun close() {
		client.close()
	}


	class Settings(db: MongoDatabase) {

		private val collection = db.getCollection("settings")

		fun filter(id: String) =
			Filters.eq("_id", id)

		fun get(id: String): Document? =
			collection.find(filter(id)).useCursor { cursor ->
				cursor.firstOrNull()
			}

		fun set(id: String, doc: Document) {
			collection.replaceOne(
				filter(id),
				doc,
				ReplaceOptions().upsert(true)
			)
		}
	}
	val settings = Settings(db)

	data class Alert(
		val typeId: String,
		val alertId: String,
		var lastNotificationNs: Long?
	)

	class Alerts(db: MongoDatabase) {

		private val collection = db.getCollection("alerts")

		fun filter(typeId: String, alertId: String) =
			Filters.eq("_id", "$typeId/$alertId")

		fun get(typeId: String, alertId: String): Alert =
			Alert(
				typeId,
				alertId,
				collection.find(filter(typeId, alertId)).useCursor { cursor ->
					cursor.firstOrNull()
						?.getLong("last_notification_ns")
				}
			)

		fun put(alert: Alert) {
			collection.replaceOne(
				filter(alert.typeId, alert.alertId),
				Document().apply {
					set("last_notification_ns", alert.lastNotificationNs)
				},
				ReplaceOptions().upsert(true)
			)
		}
	}
	val alerts = Alerts(db)

	val users = Users(db)
	val groups = Groups(db)
	val projects = Projects(db)
	val projectReaders = ProjectReaders(db)
	val jobs = Jobs(db)
	val parameters = Parameters(db)
	val micrographs = Micrographs(db)
	val micrographsAvgRot = AvgRot(db, "micrographs-avgrot")
	val jobPreprocessingFilters = PreprocessingFilters(db, "preprocessing-filters")
	val sessionPreprocessingFilters = PreprocessingFilters(db, "preprocessing-filters-session")
	val reconstructions = Reconstructions(db)
	val tomoDrgnConvergence = TomoDrgnConvergence(db)
	val twoDClasses = TwoDClasses(db)
	val tiltSeries = TiltSerieses(db)
	val tiltSeriesAvgRot = AvgRot(db, "tiltSeries-avgrot")
	val tiltSeriesDriftMetadata = DriftMetadata(db, "tiltSeries-driftmetadata")
	val refinements = Refinements(db)
	val refinementBundles = RefinementBundles(db)
	val cluster = ClusterJobs(db)
	val pypLog = PypLog(db)
	val sessions = Sessions(db)
	val particles = Particles(db)
	val particleLists = ParticleLists(db)
	val tiltExclusions = TiltExclusions(db)
	val sessionExports = SessionExports(db)
	val appTokens = AppTokens(db)
	val appTokenRequests = AppTokenRequests(db)

	init {
		// after all database collections are up and running,
		// run any needed database migrations before anything else can access the collections
		Migrations.update(this)
	}
}

fun Document.getStringId(key: String): String =
	getObjectId(key).toStringId()

fun Document.getDocument(key: String) =
	get(key, Document::class.java)

/**
 * Gets any number type, but converts to an integer first if needed
 */
fun Document.getNumberAsInt(key: String): Int? =
	when (val v = get(key)) {
		null -> null
		is Int -> v
		is Long -> v.toInt()
		is Float -> v.toInt()
		is Double -> v.toInt()
		else -> throw IllegalArgumentException("value could not be converted to an int: ${v::class.simpleName}")
	}

fun Document.getNumberAsIntOrThrow(key: String): Int =
	getNumberAsInt(key)
		?: throw NoSuchElementException("Document had no value for key: $key")

fun Document.getListOfDocuments(key: String): List<Document>? =
	getList(key, Document::class.java)

fun Document.getListOfStrings(key: String): List<String>? =
	getList(key, String::class.java)

fun <T> Document.getListOfNullables(key: String): List<T?>? {
	val r = get(key)
	if (r is List<*>) {
		@Suppress("UNCHECKED_CAST")
		return r as List<T?>
	}
	return null
}

fun Document.getListOfDoubles(key: String): List<Double> {
	val r = get(key)
	if (r is List<*>) {
		@Suppress("UNCHECKED_CAST")
		return r as List<Double>
	}
	return emptyList()
}

fun Document.getListOfListsOfDoubles(key: String): List<List<Double>> {
	val r = get(key)
	if (r is List<*>) {
		@Suppress("UNCHECKED_CAST")
		return r as List<List<Double>>
	}
	return emptyList()
}

fun Document.getListOfListsOfDocuments(key: String): List<List<Document>> {
	val r = get(key)
	if (r is List<*>) {
		@Suppress("UNCHECKED_CAST")
		return r as List<List<Document>>
	}
	return emptyList()
}

inline fun <reified T> Document.getMap(key: String): Map<String,T> {
	val r = get(key)
	if (r is Document) {
		// check the map values
		for (v in r.values) {
			if (!T::class.isInstance(v)) {
				throw IllegalArgumentException("Map value in \"$key\" is not a ${T::class}: $v (${v?.let { it::class }})")
			}
		}
		@Suppress("UNCHECKED_CAST")
		return r as Map<String,T>
	}
	return emptyMap()
}

fun Document.getKeySet(key: String): Set<String> {
	val r = get(key)
	if (r is Document) {
		return r.keys
	}
	return emptySet()
}

inline fun <reified T> Document.getMapOfLists(key: String): Map<String,List<T>> {
	val r = getMap<List<T>>(key)
	// check the list values
	for (list in r.values) {
		for (v in list) {
			if (!T::class.isInstance(v)) {
				throw IllegalArgumentException("List value in \"$key\" is not a ${T::class}: $v (${v?.let { it::class }})")
			}
		}
	}
	return r
}


/**
 * MongoDB technically supports keys with $ and . in them, but you still run into all
 * kinds of problems with their query and update tools.
 * So it's best to just not use those characters in keys.
 * See: https://www.mongodb.com/docs/manual/core/dot-dollar-considerations/
 *
 * This function will convert the given key into a safe value for MongoDB,
 * but still preserve the uniqueness properties of the original string.
 * Ie, this function won't introduce any key collisions that the original strings
 * didn't have.
 * The safe string will still look something like the orignal string too,
 * in case there are any humans around trying to make sense of things.
 */
fun String.toMongoSafeKey(): String {

	// first, remove the unsafe characters,
	// and possibly generate collisions
	val projection = this
		.replace("$", "_")
		.replace(".", "_")

	// then, undo the collisions by appending a safe encoding of the original string
	val tag = this.toByteArray(Charsets.UTF_8)
		.base62Encode()

	return "${projection}_$tag"
}

/**
 * Reverses the effects of toMongoSafeKey()
 */
fun String.fromMongoSafeKey(): String =
	split("_").last()
		.base62Decode()
		.toString(Charsets.UTF_8)

/**
 * Throws an error if the value is not a safe key value for MongoDB.
 */
fun String.mongoSafeKeyOrThrow(): String {
	if ('$' in this || '.' in this) {
		throw IllegalArgumentException("String is not a Mongo-safe key: $this\nIf you need to use this string as a key, use toMongoSafeKey()")
	}
	return this
}


/**
 * Returns true if the operation succeeded,
 * or false if an exception was thrown that indicated a duplicate key
 */
inline fun trapDuplicateKeyException(thing: () -> Unit): Boolean =
	try {

		// Do the thing! Don't let your dreams be dreams!
		thing()

		// no exception thrown = very success!
		true

	} catch (ex: DuplicateKeyException) {

		// regrettably, the Mongo Java driver doesn't seem to actually use this specific exception on insert =(
		// but we'll leave the catch handler here anyway in case it ever does
		false

	} catch (ex: MongoWriteException) {

		// instead, it seems to throw a generic write exception
		// but at least they have a unique code we can trap

		// WriteError {
		//   code=11000,
		//   message='E11000 duplicate key error collection: micromon.particleLists index: _id_ dup key: { _id: "..." }',
		//   details={}
		// }

		// although it dosen't actually seem to be documented anywhere
		// a comprehensive list of error codes is apparently WIP, see:
		// https://jira.mongodb.org/browse/DOCS-10757

		if (ex.code == 11000) {
			false
		} else {
			// don't trap the exception
			throw ex
		}
	}


fun MongoCollection<Document>.createId(): String =
	insertOne(Document())
		.insertedId
		?.asObjectId()
		?.toStringId()
		?: throw NoSuchElementException("inserted document has no id")

fun <T> MongoCollection<Document>.create(creator: (String) -> Pair<T,Document>): T =
	Database.instance.transaction {
		val id = createId()
		val (thing, doc) = creator(id)
		replaceOne(
			Filters.eq("_id", id.toObjectId()),
			doc
		)
		thing
	}
