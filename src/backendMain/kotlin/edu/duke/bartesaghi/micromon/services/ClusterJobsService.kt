package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.cluster.ClusterJobPermission
import edu.duke.bartesaghi.micromon.cluster.authClusterJobOrThrow
import io.ktor.application.ApplicationCall


actual class ClusterJobsService : IClusterJobsService {

	@Inject
	lateinit var call: ApplicationCall


	override suspend fun log(clusterJobId: String): ClusterJobLog = sanitizeExceptions {

		// authenticate the user for this job
		val user = call.authOrThrow()
		val clusterJob = user.authClusterJobOrThrow(ClusterJobPermission.Read, clusterJobId)

		clusterJob.toData()
	}

	override suspend fun arrayLog(clusterJobId: String, arrayId: Int): ClusterJobArrayLog = sanitizeExceptions {

		// authenticate the user for this job
		val user = call.authOrThrow()
		val clusterJob = user.authClusterJobOrThrow(ClusterJobPermission.Read, clusterJobId)

		// load the array element log
		val log = clusterJob.getLog(arrayId)

		return ClusterJobArrayLog(log?.result?.type, log?.result?.exitCode, log?.result?.out?.collapseProgress())
	}

	override suspend fun waitingReason(clusterJobId: String): String = sanitizeExceptions {

		// authenticate the user for this job
		val user = call.authOrThrow()
		val clusterJob = user.authClusterJobOrThrow(ClusterJobPermission.Read, clusterJobId)

		return Cluster.waitingReason(clusterJob)
	}
}
