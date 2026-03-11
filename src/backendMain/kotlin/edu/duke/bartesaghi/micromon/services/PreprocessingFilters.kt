package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getListOfDocuments
import edu.duke.bartesaghi.micromon.mongo.getListOfStrings
import edu.duke.bartesaghi.micromon.mongo.PreprocessingFilters as PreprocessingFiltersDB
import edu.duke.bartesaghi.micromon.services.PreprocessingFilterType
import org.bson.Document


open class PreprocessingFilters private constructor(
	val ownerId: String,
	val db: PreprocessingFiltersDB
) {

	companion object {

		fun ofJob(jobId: String, filterType: PreprocessingFilterType = PreprocessingFilterType.TABLE) =
			PreprocessingFilters("$jobId/${filterType.name}", Database.instance.jobPreprocessingFilters)

		fun ofSession(sessionId: String) =
			PreprocessingFilters(sessionId, Database.instance.sessionPreprocessingFilters)
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
	selectedClasses?.let { doc.set("selectedClasses", it) }
	selectedClassesReal?.let { doc.set("selectedClassesReal", it) }
	selectedClassesFourier?.let { doc.set("selectedClassesFourier", it) }
}

private fun Document.getListOfInts(key: String): List<Int>? {
	val r = get(key) ?: return null
	if (r is List<*>) {
		return r.mapNotNull { (it as? Number)?.toInt() }
	}
	return null
}

private fun PreprocessingFilter.Companion.fromDoc(doc: Document): PreprocessingFilter {
	val legacyClasses = doc.getListOfInts("selectedClasses")
	val real = doc.getListOfInts("selectedClassesReal") ?: legacyClasses
	val fourier = doc.getListOfInts("selectedClassesFourier") ?: legacyClasses
	return PreprocessingFilter(
		name = doc.getString("name"),
		ranges = doc.getListOfDocuments("ranges")
			?.map { rangeDoc -> PreprocessingPropRange(
				propId = rangeDoc.getString("prop"),
				min = rangeDoc.getDouble("min"),
				max = rangeDoc.getDouble("max")
			)}
			?: emptyList(),
		excludedIds = doc.getListOfStrings("excluded")
			?: emptyList(),
		selectedClasses = legacyClasses,
		selectedClassesReal = real,
		selectedClassesFourier = fourier
	)
}
