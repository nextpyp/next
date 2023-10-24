package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getListOfDocuments
import edu.duke.bartesaghi.micromon.mongo.getListOfStrings
import edu.duke.bartesaghi.micromon.mongo.PreprocessingFilters as PreprocessingFiltersDB
import org.bson.Document


open class PreprocessingFilters private constructor(
	val ownerId: String,
	val db: PreprocessingFiltersDB
) {

	companion object {

		fun ofJob(jobId: String) =
			PreprocessingFilters(jobId, Database.jobPreprocessingFilters)

		fun ofSession(sessionId: String) =
			PreprocessingFilters(sessionId, Database.sessionPreprocessingFilters)
	}

	fun getAll(): List<PreprocessingFilter> {
		return db.getAll(ownerId) { cursor ->
			cursor
				.map { PreprocessingFilter.fromDoc(it) }
				.toList()
		}
	}

	operator fun get(name: String): PreprocessingFilter? {
		return db.get(ownerId, name)
			?.let { PreprocessingFilter.fromDoc(it) }
	}

	fun save(filter: PreprocessingFilter) {
		db.write(ownerId, filter.name) {
			filter.toDoc(it)
		}
	}

	fun delete(name: String) {
		db.delete(ownerId, name)
	}
}


private fun PreprocessingFilter.toDoc(doc: Document) {
	doc.set("name", name)
	doc.set("ranges", ranges.map { range ->
		Document().apply {
			set("prop", range.propId)
			set("min", range.min)
			set("max", range.max)
		}
	})
	doc.set("excluded", excludedIds)
}

private fun PreprocessingFilter.Companion.fromDoc(doc: Document) = PreprocessingFilter(
	name = doc.getString("name"),
	ranges = doc.getListOfDocuments("ranges")
		?.map { rangeDoc -> PreprocessingPropRange(
			propId = rangeDoc.getString("prop"),
			min = rangeDoc.getDouble("min"),
			max = rangeDoc.getDouble("max")
		)}
		?: emptyList(),
	excludedIds = doc.getListOfStrings("excluded")
		?: emptyList()
)
