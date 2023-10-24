package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.jobs.AuthInfo
import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.jobs.authJob
import io.ktor.application.*
import io.kvision.remote.RemoteOption
import io.kvision.remote.ServiceException


/**
 * Temporary tool to show unified refinement results from all refinement jobs
 */
actual class BlocksService : IBlocksService, Service {

	@Inject
	override lateinit var call: ApplicationCall

	private fun String.authJob(permission: ProjectPermission): AuthInfo<Job> =
		authJob(permission, this)

	override suspend fun listFilters(jobId: String): List<String> = sanitizeExceptions {

		jobId.authJob(ProjectPermission.Read)

		return PreprocessingFilters.ofJob(jobId).getAll()
			.map { it.name }
	}

	override suspend fun getFilter(jobId: String, name: String): PreprocessingFilter = sanitizeExceptions {

		jobId.authJob(ProjectPermission.Read)

		return PreprocessingFilters.ofJob(jobId)[name]
			?: throw ServiceException("no filter named $name")
	}

	override suspend fun saveFilter(jobId: String, filter: PreprocessingFilter) = sanitizeExceptions {

		jobId.authJob(ProjectPermission.Write)

		PreprocessingFilters.ofJob(jobId).save(filter)
	}

	override suspend fun deleteFilter(jobId: String, name: String) = sanitizeExceptions {

		jobId.authJob(ProjectPermission.Write)

		PreprocessingFilters.ofJob(jobId).delete(name)
	}

	override suspend fun filterOptions(search: String?, initial: String?, state: String?): List<RemoteOption> = sanitizeExceptions {

		val jobId = state
			?: throw IllegalArgumentException("no job given")

		jobId.authJob(ProjectPermission.Read)

		val header = listOf(
			RemoteOption(NoneFilterOption, "(None)"),
			RemoteOption(divider = true)
		)

		val options = PreprocessingFilters.ofJob(jobId).getAll()
			.map { filter ->
				RemoteOption(filter.name)
			}

		return header + options
	}
}
