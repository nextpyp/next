package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.services.RefinementData
import org.bson.Document
import java.time.Instant


class Refinement(
	val jobId: String,
	val dataId: String,
	val iteration: Int,
	val timestamp: Instant
) {

	companion object {

		fun get(jobId: String, dataId: String): Refinement? =
			Database.instance.refinements.get(jobId, dataId)
				?.let { fromDoc(it) }

		fun getAll(jobId: String): List<Refinement> =
			Database.instance.refinements.getAll(jobId) { cursor ->
				cursor
					.map { fromDoc(it) }
					.toList()
			}

		fun fromDoc(doc: Document) = Refinement(
			jobId = doc.getString("jobId"),
			dataId = doc.getString("dataId"),
			iteration = doc.getInteger("iteration") ?: 0,
			timestamp = Instant.ofEpochMilli(doc.getLong("timestamp"))
		)

		fun deleteAllNotIteration(jobId: String, iteration: Int) {
			Database.instance.refinements.deleteAllNotIteration(jobId, iteration)
		}
	}

	fun toDoc() = Document().apply {
		set("jobId", jobId)
		set("dataId", dataId)
		set("iteration", iteration)
		set("timestamp", timestamp.toEpochMilli())
	}

	fun toData() = RefinementData(
		jobId = jobId,
		dataId = dataId,
		iteration = iteration,
		timestamp = timestamp.toEpochMilli()
	)

	fun write() {
		Database.instance.refinements.write(jobId, dataId) {
			putAll(toDoc())
		}
	}
}
