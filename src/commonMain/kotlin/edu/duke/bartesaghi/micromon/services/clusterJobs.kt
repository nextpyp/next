package edu.duke.bartesaghi.micromon.services

import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


/**
 * Service for interacting with user projects
 */
@KVService
interface IClusterJobsService {

	@KVBindingRoute("clusterJobs/log")
	suspend fun log(clusterJobId: String): ClusterJobLog

	@KVBindingRoute("clusterJobs/arrayLog")
	suspend fun arrayLog(clusterJobId: String, arrayId: Int): ClusterJobArrayLog

	@KVBindingRoute("clusterJobs/waitingReason")
	suspend fun waitingReason(clusterJobId: String): String
}


@Serializable
class ClusterJobLog(
	val representativeCommand: String,
	val commandParams: String?,
	val submitFailure: String?,
	val template: String?,
	val launchScript: String?,
	val launchResult: ClusterJobLaunchResultData?,
	val resultType: ClusterJobResultType?,
	val exitCode: Int?,
	val log: String?,
	val arraySize: Int?,
	val failedArrayIds: List<Int>
)


@Serializable
class ClusterJobLaunchResultData(
	val command: String?,
	val out: String,
	val success: Boolean
)


@Serializable
class ClusterJobArrayLog(
	val resultType: ClusterJobResultType?,
	val exitCode: Int?,
	val log: String?
)
