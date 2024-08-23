package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.linux.userprocessor.createDirsIfNeededAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.writeStringAs
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.Micrograph
import edu.duke.bartesaghi.micromon.pyp.TiltSeries
import edu.duke.bartesaghi.micromon.services.PreprocessingFilter
import edu.duke.bartesaghi.micromon.services.PreprocessingFilters
import io.kvision.remote.ServiceException
import java.nio.file.Path
import kotlin.io.path.div


interface FilteredJob {

	val filters: PreprocessingFilters

	fun resolveFilter(filter: PreprocessingFilter): List<String>

	suspend fun writeFilter(name: String, dir: Path, osUsername: String?) {

		// look for the filter in the upstream block
		val filter = filters[name]
			?: throw ServiceException("unrecognized filter: $name")

		// write out the micrographs file to the job folder
		dir.createDirsIfNeededAs(osUsername)
		val file = dir / "${dir.fileName}.micrographs"
		file.writeStringAs(osUsername, resolveFilter(filter).joinToString("\n"))
	}

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
