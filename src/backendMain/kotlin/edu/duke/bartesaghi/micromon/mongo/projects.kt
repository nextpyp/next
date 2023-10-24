package edu.duke.bartesaghi.micromon.mongo

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.projects.Project
import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.jobs.JobOwner
import edu.duke.bartesaghi.micromon.services.ProjectPermission
import edu.duke.bartesaghi.micromon.services.RunStatus
import io.kvision.remote.ServiceException
import org.bson.Document
import org.bson.conversions.Bson
import java.util.NoSuchElementException


class Projects {

	private val collection = Database.db.getCollection("projects")

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["userId"] = 1
		})
	}

	companion object {

		fun id(userId: String, projectId: String): String =
			"$userId/$projectId"
	}


	fun clear() =
		collection.deleteMany(Document())

	fun filter(userId: String, projectId: String) =
		Filters.eq("_id", id(userId, projectId))

	fun filterRunningOrWaiting() =
		Filters.elemMatch("running", Filters.`in`("status", RunStatus.Running.id, RunStatus.Waiting.id))

	/** gets project data, all but the running field */
	fun get(userId: String, projectId: String) =
		collection.find(filter(userId, projectId))
			// don't load the (potentially large) running list when just looking at basic project info
			.projection(Projections.exclude("running"))
			.useCursor { cursor ->
				cursor.firstOrNull()
			}

	/** gets project data, but only the running field */
	fun getWithOnlyRunning(userId: String, projectId: String) =
		collection.find(filter(userId, projectId))
			.projection(Projections.include("running"))
			.useCursor { cursor ->
				cursor.firstOrNull()
			}

	fun getProject(userId: String, projectId: String) =
		get(userId, projectId)?.let { Project(it) }

	fun getProjectOrThrow(userId: String, projectId: String) =
		getProject(userId, projectId)
			?: throw NoSuchElementException("no project with userId=$userId and projectId=$projectId")

	fun exists(userId: String, projectId: String) =
		collection.countDocuments(filter(userId, projectId)) > 0

	fun <R> getAllOwnedBy(userId: String, block: (Sequence<Document>) -> R): R =
		collection.find(Filters.eq("userId", userId)).useCursor(block)

	fun <R> getAllRunningOrWaitingOwnedBy(userId: String, block: (Sequence<Document>) -> R): R =
		collection.find(
			Filters.and(
				Filters.eq("userId", userId),
				filterRunningOrWaiting()
			)
		).useCursor(block)

	fun <R> getAllReadableBy(userId: String, block: (Sequence<Document>) -> R): R =
		Database.projectReaders.get(userId)
			.asSequence()
			.mapNotNull { collection.find(Filters.eq("_id", it)).firstOrNull() }
			.let { block(it) }

	fun <R> getAllRunningOrWaiting(block: (Sequence<Document>) -> R): R =
		collection.find(filterRunningOrWaiting()).useCursor(block)

	fun create(userId: String, projectId: String, doccer: Document.() -> Unit) {
		collection.replaceOne(
			filter(userId, projectId),
			Document().apply {
				set("userId", userId)
				set("projectId", projectId)
				doccer()
			},
			ReplaceOptions().upsert(true)
		)
	}

	fun update(userId: String, projectId: String, vararg updates: Bson) {
		collection.updateOne(
			filter(userId, projectId),
			Updates.combine(updates.toList())
		)
	}

	fun delete(userId: String, projectId: String) {
		collection.deleteOne(filter(userId, projectId))
	}
}


class ProjectReaders {

	private val collection = Database.db.getCollection("projectReaders")

	private fun filter(userId: String) =
		Filters.eq("_id", userId)

	/**
	 * gets the document ids in the `projects` collection that can be read by the user
	 */
	fun get(readerUserId: String): Set<String> =
		collection.find(filter(readerUserId))
			.firstOrNull()
			?.keys
			?.map { it.fromMongoSafeKey() }
			?.toSet()
			?: emptySet()

	fun set(readerUserId: String, ownerUserId: String, projectId: String) {
		collection.updateOne(
			filter(readerUserId),
			Updates.set(Projects.id(ownerUserId, projectId).toMongoSafeKey(), true),
			UpdateOptions().upsert(true)
		)
	}

	fun unset(readerUserId: String, ownerUserId: String, projectId: String) {
		collection.updateOne(
			filter(readerUserId),
			Updates.unset(Projects.id(ownerUserId, projectId).toMongoSafeKey())
		)
	}
}


fun User.authProjectOrThrow(permission: ProjectPermission, userId: String, projectId: String): Project {

	val project = Database.projects.getProject(userId, projectId)
		?: throw ServiceException("project not found")

	return if (permission in permissions(project)) {
		project
	} else {
		throw AuthenticationException("access to project denied")
			.withInternal("$permission requested by user $id to project $userId/$projectId")
	}
}

fun User?.permissions(project: Project): Set<ProjectPermission> =
	when {
		// not logged in? you get nothing
		this == null -> emptySet()

		// admins and owners can do everything
		isAdmin -> setOf(ProjectPermission.Read, ProjectPermission.Write)

		// owners can do everything
		id == project.userId -> setOf(ProjectPermission.Read, ProjectPermission.Write)

		// readers can only read
		id in project.readerUserIds -> setOf(ProjectPermission.Read)

		else -> emptySet()
	}


object JobNumberLock

class Jobs {

	private val collection = Database.db.getCollection("jobs")

	init {
		// create indices to speed up common but slow operations
		// don't worry though, mongo will only actually create the index if it doesn't already exist
		collection.createIndex(Document().apply {
			this["projectId"] = 1
		})
		collection.createIndex(Document().apply {
			this["userId"] = 1
			this["projectId"] = 1
		})
	}

	fun filter(jobId: String) =
		Filters.eq("_id", jobId.toObjectId())

	fun filterInProject(userId: String, projectId: String) =
		Filters.and(
			Filters.eq("userId", userId),
			Filters.eq("projectId", projectId)
		)

	fun get(jobId: String) =
		collection.find(filter(jobId)).useCursor { cursor ->
			cursor.firstOrNull()
		}

	fun <R> getAllInProject(userId: String, projectId: String, block: (Sequence<Document>) -> R): R =
		collection.find(filterInProject(userId, projectId)).useCursor(block)

	/**
	 * Creates a new job and returns the id and number.
	 */
	fun create(userId: String, projectId: String, doccer: Document.() -> Unit): Pair<String,Int> {

		// assign the job a per-project auto-incrementing number
		synchronized(JobNumberLock) {
			// NOTE: synchronize all job creations so we don't get data races if anything weird happens,
			//       like a user creating two jobs at the exact same time somehow
			// NOTE: we could use database transactions to accomplish the same goal here, but this is
			//       much much simpler and the marginally higher performance of transactions is totally unnecessary

			// get an auto-incrementing number for the job
			val jobNumber = getAllInProject(userId, projectId) { docs ->
				docs
					.maxOfOrNull { it.getInteger("jobNumber") ?: 0 }
					?.let { highestJobNumber -> highestJobNumber + 1 }
					?: 1
			}

			val result = collection.insertOne(
				Document().apply {
					doccer()
					set("jobNumber", jobNumber)
				}
			)

			val jobId = result.insertedId?.asObjectId()?.value?.toStringId()
				?: throw NoSuchElementException("inserted document had no id")

			return jobId to jobNumber
		}
	}

	fun update(jobId: String, vararg updates: Bson) =
		update(jobId, updates.toList())

	fun update(jobId: String, updates: List<Bson>) {
		collection.updateOne(
			filter(jobId),
			Updates.combine(updates)
		)
	}

	fun delete(jobId: String) {
		collection.deleteOne(filter(jobId))
	}

	fun deleteAllInProject(userId: String, projectId: String) {
		collection.deleteMany(filterInProject(userId, projectId))
	}
}

fun User.authJobOrThrow(permission: ProjectPermission, jobId: String): Job {
	val job = Job.fromIdOrThrow(jobId)
	authProjectOrThrow(permission, job.userId, job.projectId)
	return job
}


fun User.authClusterJobOrThrow(permission: ProjectPermission, clusterJobId: String): ClusterJob {

	val clusterJob = ClusterJob.get(clusterJobId)
		?: throw ServiceException("cluster job not found")

	val jobOwner = JobOwner.fromString(clusterJob.ownerId)
		?: throw AuthenticationException("access to cluster job denied")
			.withInternal("$permission requested by user $id to cluster job $clusterJobId")

	authJobOrThrow(permission, jobOwner.jobId)

	return clusterJob
}
