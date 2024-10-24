package edu.duke.bartesaghi.micromon.mongo.migrations

import edu.duke.bartesaghi.micromon.mongo.DatabaseConnection
import edu.duke.bartesaghi.micromon.mongo.useCursor
import edu.duke.bartesaghi.micromon.tryCleanupStackTraceOrDont
import org.bson.Document
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess


typealias MigrationRunner = (database: DatabaseConnection, log: Logger) -> Unit

object Migrations {

	private val log = LoggerFactory.getLogger("Migrations")

	/**
	 * The list of all migrations that have ever existed.
	 *
	 * Each runner function should be idempotent.
	 * That is, running it multiple times should always result in the same final state.
	 */
	private val migrations: List<Pair<String,MigrationRunner>> = listOf(
		"particles" to ::migrationParticles,
		"tkhashes" to ::migrationTokenHashes
	)

	init {
		// make sure there are no duplicate ids
		val duplicateIds = migrations
			.groupBy { it.first }
			.filter { (_, entries) -> entries.size > 1 }
		if (duplicateIds.isNotEmpty()) {
			throw Error("""
				|Invalid migration configuration.
				|Duplicate ids: $duplicateIds
			""".trimMargin())
		}
	}


	class AppliedMigrations(database: DatabaseConnection) {

		val collection = database.db.getCollection("migrations")

		/**
		 * Returns the IDs of the applied migrations
		 */
		fun get(): Set<String> =
			collection.find().useCursor { cursor ->
				cursor
					.map { doc -> doc.getString("_id") }
					.toSet()
			}

		fun add(id: String) {
			collection.insertOne(Document().apply {
				this["_id"] = id
			})
		}
	}

	private fun DatabaseConnection.applied() = AppliedMigrations(this)


	/**
	 * Run any necessary migrations, exactly once each.
	 * Call this function exactly once at startup, and never more than once.
	 */
	fun update(database: DatabaseConnection) {

		// handle any first-time database init
		val applied = database.applied()
		if (database.isNew) {
			log.info("Initializing database migrations.")
			for ((id, _) in migrations) {
				applied.add(id)
			}
		}

		// get the migrations we need to run
		val appliedIds = applied.get()
		val migrationsToRun = migrations
			.filter { (id, _) -> id !in appliedIds }
		if (migrationsToRun.isEmpty()) {
			log.info("No migrations to run. Everything up to date.")
			return
		}

		log.info("""
			|Need to run ${migrationsToRun.size} migrations:
			|	${migrationsToRun.joinToString("\n\t") { (id, _) -> id }}
		""".trimMargin())

		for ((id, runner) in migrationsToRun) {

			// run the migration inside a transaction
			database.client.startSession().use { session ->
				session.startTransaction()
				try {

					// try to run the migration
					log.info("Running migration $id ...")
					runner(database, log)
					applied.add(id)
					session.commitTransaction()
					log.info("Migration $id finished successfully")

				} catch (t: Throwable) {

					// oh no! abort the transaction so it's like the migration never ran
					log.error("Migration $id failed", t.tryCleanupStackTraceOrDont())
					session.abortTransaction()
					log.info("""
						|******************************************
						|* Migration failure!
						|*    Database changes for this migration were not applied.
						|*    Aborting any remaining migrations.
						|*    Exiting process immediately to protect data integrity.
						|*    It is unsafe to continue running the server with database in an inconsistent state.
						|*    Restarting the server will try to run the same migration again, and it may fail in the same way.
						|*    If the failure is persistent, contact the application developers with any error info shown above.
						|******************************************
					""".trimMargin())
					// goodness, hopefully no users ever actually see this message! =P

					// no really though, burn this process all the way down
					// to make sure none of the current code (which expects a migrated database state)
					// can corrupt any of the current data
					exitProcess(1)
				}
			}
		}

		log.info("All migrations finished successfully. Everything up to date. =)")
	}
}
