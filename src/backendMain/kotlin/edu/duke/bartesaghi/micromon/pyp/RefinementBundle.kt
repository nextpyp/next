package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.services.RefinementBundleData
import org.bson.Document
import java.time.Instant


class RefinementBundle(
	val jobId: String,
	val refinementBundleId: String,
	val iteration: Int,
	val timestamp: Instant
) {

	companion object {

		fun get(jobId: String, dataId: String): RefinementBundle? =
			Database.refinementBundles.get(jobId, dataId)
				?.let { fromDoc(it) }

		fun getAll(jobId: String): List<RefinementBundle> =
			Database.refinementBundles.getAll(jobId) { cursor ->
				cursor
					.map { fromDoc(it) }
					.toList()
			}

		fun fromDoc(doc: Document) = RefinementBundle(
			jobId = doc.getString("jobId"),
			refinementBundleId = doc.getString("refinementBundleId"),
			iteration = doc.getInteger("iteration") ?: 0,
			timestamp = Instant.ofEpochMilli(doc.getLong("timestamp"))
		)

		fun deleteAllNotIteration(jobId: String, iteration: Int) {
			Database.refinementBundles.deleteAllNotIteration(jobId, iteration)
		}
	}

	fun toDoc() = Document().apply {
		set("jobId", jobId)
		set("refinementBundleId", refinementBundleId)
		set("iteration", iteration)
		set("timestamp", timestamp.toEpochMilli())
	}

	fun toData() = RefinementBundleData(
		jobId = jobId,
		refinementBundleId = refinementBundleId,
		iteration = iteration,
		timestamp = timestamp.toEpochMilli()
	)

	fun write() {
		Database.refinementBundles.write(jobId, refinementBundleId) {
			putAll(toDoc())
		}
	}
}
