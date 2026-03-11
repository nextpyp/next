package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.jobs.AuthInfo
import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.jobs.authJob
import io.ktor.application.*
import io.ktor.features.*
import io.kvision.remote.RemoteOption
import io.kvision.remote.ServiceException


/**
 * Temporary tool to show unified refinement results from all refinement jobs
 */
actual class BlocksService : IBlocksService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	private fun String.authJob(permission: ProjectPermission): AuthInfo<Job> =
		authJob(this, permission)

	override suspend fun listFilters(jobId: String, filterType: PreprocessingFilterType): List<String> = sanitizeExceptions {

		jobId.authJob(ProjectPermission.Read)

		return PreprocessingFilters.ofJob(jobId, filterType).getAll()
			.map { it.name }
	}

	override suspend fun getFilter(jobId: String, name: String, filterType: PreprocessingFilterType): PreprocessingFilter = sanitizeExceptions {

		jobId.authJob(ProjectPermission.Read)

		return PreprocessingFilters.ofJob(jobId, filterType)[name]
			?: throw ServiceException("no filter named $name")
	}

	override suspend fun saveFilter(jobId: String, filter: PreprocessingFilter, filterType: PreprocessingFilterType) = sanitizeExceptions {

		jobId.authJob(ProjectPermission.Write)

		PreprocessingFilters.ofJob(jobId, filterType).save(filter)
	}

	override suspend fun deleteFilter(jobId: String, name: String, filterType: PreprocessingFilterType) = sanitizeExceptions {

		jobId.authJob(ProjectPermission.Write)

		PreprocessingFilters.ofJob(jobId, filterType).delete(name)
	}

	override suspend fun filterOptions(search: String?, initial: String?, state: String?): List<RemoteOption> = sanitizeExceptions {

		val (jobId, filterType) = parseFilterOptionsState(state)

		jobId.authJob(ProjectPermission.Read)

		val typeLabel = when (filterType) {
			PreprocessingFilterType.TABLE -> "Table"
			PreprocessingFilterType.PRISMPYP -> "prismpyp"
		}
		val header = listOf(
			RemoteOption(NoneFilterOption, "(None)"),
			RemoteOption(divider = true)
		)

		val options = PreprocessingFilters.ofJob(jobId, filterType).getAll()
			.map { filter ->
				RemoteOption(filter.name, "${filter.name} ($typeLabel)")
			}

		return header + options
	}

	private fun parseFilterOptionsState(state: String?): Pair<String, PreprocessingFilterType> {
		val parts = state?.split(",", limit = 2) ?: throw BadRequestException("no state given")
		if (parts.size != 2) throw BadRequestException("state must be jobId,filterType")
		val jobId = parts[0].trim()
		val type = when (parts[1].trim().uppercase()) {
			"TABLE" -> PreprocessingFilterType.TABLE
			"PRISMPYP" -> PreprocessingFilterType.PRISMPYP
			else -> throw BadRequestException("filterType must be TABLE or PRISMPYP")
		}
		return jobId to type
	}
}
