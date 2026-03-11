package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.linux.userprocessor.createDirsIfNeededAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.writeStringAs
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.Micrograph
import edu.duke.bartesaghi.micromon.pyp.TiltSeries
import edu.duke.bartesaghi.micromon.services.PreprocessingFilter
import edu.duke.bartesaghi.micromon.services.PreprocessingFilterType
import edu.duke.bartesaghi.micromon.services.PreprocessingFilters
import io.kvision.remote.ServiceException
import java.nio.file.Path
import kotlin.io.path.div


interface FilteredJob {

	val filterableJobId: String
	val filters: PreprocessingFilters

	fun resolveFilter(filter: PreprocessingFilter): List<String>

	suspend fun writeFilter(name: String, dir: Path, osUsername: String?, filterType: PreprocessingFilterType) {

		val filtersForType = PreprocessingFilters.ofJob(filterableJobId, filterType)
		val filter = filtersForType[name]
			?: throw ServiceException("unrecognized filter: $name")

		dir.createDirsIfNeededAs(osUsername)
		val realClasses = filter.selectedClassesReal ?: filter.selectedClasses
		val fourierClasses = filter.selectedClassesFourier ?: filter.selectedClasses
		if (realClasses != null || fourierClasses != null) {
			// class-based filter: write one file for real, one for Fourier
			val realFile = dir / "${dir.fileName}.real.classes"
			realFile.writeStringAs(osUsername, (realClasses ?: emptyList()).joinToString("\n"))
			val fourierFile = dir / "${dir.fileName}.fourier.classes"
			fourierFile.writeStringAs(osUsername, (fourierClasses ?: emptyList()).joinToString("\n"))
		} else {
			// micrograph-based filter: write list of micrograph IDs
			val file = dir / "${dir.fileName}.micrographs"
			file.writeStringAs(osUsername, resolveFilter(filter).joinToString("\n"))
		}
	}

	companion object {

		fun resolveFilterMicrographs(jobId: String, filter: PreprocessingFilter): List<String> =
			Database.instance.micrographs.getAll(jobId) { cursor ->
				cursor
					.map { Micrograph(it) }
					.filter { it.isInRanges(filter) && it.micrographId !in filter.excludedIds }
					.map { it.micrographId }
					.toList()
			}

		fun resolveFilterTiltSeries(jobId: String, filter: PreprocessingFilter): List<String> =
			Database.instance.tiltSeries.getAll(jobId) { cursor ->
				cursor
					.map { TiltSeries(it) }
					.filter { it.isInRanges(filter) && it.tiltSeriesId !in filter.excludedIds }
					.map { it.tiltSeriesId }
					.toList()
			}
	}
}
