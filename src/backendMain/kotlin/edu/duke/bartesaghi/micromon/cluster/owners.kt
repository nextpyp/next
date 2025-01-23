package edu.duke.bartesaghi.micromon.cluster

import edu.duke.bartesaghi.micromon.AuthException
import edu.duke.bartesaghi.micromon.User
import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.jobs.JobOwner
import edu.duke.bartesaghi.micromon.mongo.authJobOrThrow
import edu.duke.bartesaghi.micromon.services.ProjectPermission
import edu.duke.bartesaghi.micromon.services.SessionPermission
import edu.duke.bartesaghi.micromon.sessions.Session
import edu.duke.bartesaghi.micromon.sessions.authSessionOrThrow
import io.kvision.remote.ServiceException


sealed class ClusterJobOwner(
	val id: String
) {
	class Job(val job: edu.duke.bartesaghi.micromon.jobs.Job) : ClusterJobOwner(job.idOrThrow) {

		override fun toString(): String =
			"Job[${job.baseConfig.id},${job.id}]"
	}

	class Session(val session: edu.duke.bartesaghi.micromon.sessions.Session) : ClusterJobOwner(session.idOrThrow) {

		override fun toString(): String =
			"Session[${session.type},${session.id}]"
	}
}


fun ClusterJob.findOwner(): ClusterJobOwner? {

	if (ownerId == null) {
		return null
	}

	// try jobs
	JobOwner.fromString(ownerId)?.let { jobOwner ->
		return ClusterJobOwner.Job(Job.fromIdOrThrow(jobOwner.jobId))
	}

	// try sessions
	Session.fromId(ownerId)?.let { session ->
		return ClusterJobOwner.Session(session)
	}

	// owner was unrecognized
	return null
}


fun ClusterJob.findOwnerOrThrow(): ClusterJobOwner {

	if (ownerId == null) {
		throw NoSuchElementException("cluster job has no owner")
	}

	return findOwner()
		?: throw NoSuchElementException("cluster job owner was not recognized: $ownerId")
}


enum class ClusterJobPermission {

	Read,
	Write;


	fun toProject(): ProjectPermission =
		when (this) {
			Read -> ProjectPermission.Read
			Write -> ProjectPermission.Write
		}

	fun toSession(): SessionPermission =
		when (this) {
			Read -> SessionPermission.Read
			Write -> SessionPermission.Write
		}
}


fun User.authClusterJobOrThrow(permission: ClusterJobPermission, clusterJobId: String): ClusterJob {

	val clusterJob = ClusterJob.get(clusterJobId)
		?: throw ServiceException("cluster job not found")

	when (val owner = clusterJob.findOwner()) {

		null -> {
			throw AuthException("access to cluster job denied")
				.withInternal("$permission requested by user $id to cluster job $clusterJobId, but owner ${clusterJob.ownerId} was not found")
		}

		is ClusterJobOwner.Job -> authJobOrThrow(permission.toProject(), owner.job.idOrThrow)
		is ClusterJobOwner.Session -> authSessionOrThrow(owner.session.idOrThrow, permission.toSession())
	}

	return clusterJob
}
