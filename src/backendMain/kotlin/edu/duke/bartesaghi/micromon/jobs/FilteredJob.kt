package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.Micrograph
import edu.duke.bartesaghi.micromon.pyp.TiltSeries
import edu.duke.bartesaghi.micromon.services.PreprocessingFilter
import edu.duke.bartesaghi.micromon.services.PreprocessingFilters


interface FilteredJob {

	val filters: PreprocessingFilters

	fun resolveFilter(filter: PreprocessingFilter): List<String>


	companion object {

		fun resolveFilterMicrographs(jobId: String, filter: PreprocessingFilter): List<String> =
			Database.micrographs.getAll(jobId) { cursor ->
				cursor
					.map { Micrograph(it) }
					.filter { it.isInRanges(filter) && it.micrographId !in filter.excludedIds }
					.map { it.micrographId }
					.toList()
			}

		fun resolveFilterTiltSeries(jobId: String, filter: PreprocessingFilter): List<String> =
			Database.tiltSeries.getAll(jobId) { cursor ->
				cursor
					.map { TiltSeries(it) }
					.filter { it.isInRanges(filter) && it.tiltSeriesId !in filter.excludedIds }
					.map { it.tiltSeriesId }
					.toList()
			}
	}
}
