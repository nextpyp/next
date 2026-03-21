package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getListOfDocuments
import edu.duke.bartesaghi.micromon.mongo.getListOfStrings
import edu.duke.bartesaghi.micromon.mongo.PreprocessingFilters as PreprocessingFiltersDB
import edu.duke.bartesaghi.micromon.services.PreprocessingFilterType
import org.bson.Document


open class PreprocessingFilters private constructor(
	val ownerId: String,
	val db: PreprocessingFiltersDB,
	/**
	 * Before per-type namespaces, table filters lived under the bare preprocessing [jobId] in Mongo.
	 * We merge reads from [ownerId] (`jobId/TABLE`) and this legacy id; saves go to [ownerId] and
	 * remove a same-named legacy row so lists do not duplicate.
	 */
	private val legacyTableOwnerId: String? = null
) {

	companion object {

		fun ofJob(jobId: String, filterType: PreprocessingFilterType = PreprocessingFilterType.TABLE) =
			PreprocessingFilters(
				ownerId = "$jobId/${filterType.name}",
				db = Database.instance.jobPreprocessingFilters,
				legacyTableOwnerId = if (filterType == PreprocessingFilterType.TABLE) jobId else null
			)

		fun ofSession(sessionId: String) =
			PreprocessingFilters(sessionId, Database.instance.sessionPreprocessingFilters)
	}

	fun getAll(): List<PreprocessingFilter> {
		val current = db.getAll(ownerId) { cursor ->
			cursor
				.map { PreprocessingFilter.fromDoc(it) }
				.toList()
		}
		val legacy = legacyTableOwnerId?.let { lid ->
			db.getAll(lid) { cursor ->
				cursor
					.map { PreprocessingFilter.fromDoc(it) }
					.toList()
			}
		} ?: emptyList()
		if (legacy.isEmpty()) {
			return current
		}
		val currentNames = current.map { it.name }.toSet()
		return current + legacy.filter { it.name !in currentNames }
	}

	operator fun get(name: String): PreprocessingFilter? {
		return db.get(ownerId, name)
			?.let { PreprocessingFilter.fromDoc(it) }
			?: legacyTableOwnerId?.let { lid ->
				db.get(lid, name)?.let { PreprocessingFilter.fromDoc(it) }
			}
	}

	fun save(filter: PreprocessingFilter) {
		db.write(ownerId, filter.name) {
			filter.toDoc(it)
		}
		legacyTableOwnerId?.let { lid ->
			if (db.get(lid, filter.name) != null) {
				db.delete(lid, filter.name)
			}
		}
	}

	fun delete(name: String) {
		db.delete(ownerId, name)
		legacyTableOwnerId?.let { lid ->
			db.delete(lid, name)
		}
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
