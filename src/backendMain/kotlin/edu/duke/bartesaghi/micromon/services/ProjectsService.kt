package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.projects.Project
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.cluster.slurm.Template
import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.jobs.JobRunner
import edu.duke.bartesaghi.micromon.jobs.jobInfo
import edu.duke.bartesaghi.micromon.linux.DU
import edu.duke.bartesaghi.micromon.mongo.*
import edu.duke.bartesaghi.micromon.nodes.NodeConfigs
import edu.duke.bartesaghi.micromon.nodes.Workflow
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Workflows
import io.ktor.application.ApplicationCall
import io.kvision.remote.ServiceException
import kotlinx.coroutines.launch
import java.time.Instant


actual class ProjectsService : IProjectsService {

	@Inject
	lateinit var call: ApplicationCall

	override suspend fun list(userId: String): List<ProjectData> = sanitizeExceptions {

		// authenticate the user
		val user = call.authOrThrow()

		// users can list their own projects, or admins can list others' projects
		if (user.id != userId && !user.isAdmin) {
			throw AuthException("access denied")
				.withInternal("for user ${user.id} to access $userId")
		}


		val projects =
			if (user.isAdmin && userId == user.id) {

				// admins listing their own projects always get all projects instead
				Database.instance.projects.getAll { docs ->
					docs
						.map { Project(it).toData(user) }
						.toList()
				}

			} else {
				// get the projects owned by the target user
				val ownedProjects = Database.instance.projects.getAllOwnedBy(userId) { docs ->
					docs
						.map { Project(it).toData(user) }
						.toList()
				}

				// and any projects they can read too
				val readableProjects = Database.instance.projects.getAllReadableBy(userId) { docs ->
					docs
						.map { Project(it).toData(user) }
						.toList()
				}

				// then mix them together
				ownedProjects + readableProjects
			}

		// sort in reverse-chronological order
		return projects.sortedByDescending { it.created }
	}

	override suspend fun create(name: String): ProjectData = sanitizeExceptions {

		// authenticate the user
		val user = call.authOrThrow()
		user.notDemoOrThrow()

		// check project limits, if any
		Config.instance.web.maxProjectsPerUser
			?.let { limit ->
				if (Database.instance.projects.countOwnedBy(user.id) >= limit) {
					throw ServiceException("Can't create new project, limit of $limit reached")
				}
			}

		val project = Database.instance.transaction {

			// make sure a project with that name doesn't already exist
			val projectId = Project.makeId(name)
			if (Database.instance.projects.exists(user.id, projectId)) {
				throw ServiceException("A project with that name already exists")
			}

			// choose a new project number to be one more than the highest known project number
			val projectNumber = Database.instance.projects.getAllOwnedBy(user.id) { docs ->
				docs
					.maxOfOrNull { doc -> doc.getInteger("projectNumber") ?: 0 }
					?.let { highestProjectNumber -> highestProjectNumber + 1 }
					?: 1
			}

			// make a new project
			Project(
				userId = user.id,
				osUsername = user.osUsername,
				projectId = projectId,
				projectNumber = projectNumber,
				name = name,
				created = Instant.now()
			)
		}

		project.create()

		return project.toData(user)
	}

	override suspend fun delete(userId: String, projectId: String) = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		val project = user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// don't delete a project that has an active run
		if (project.getRuns().any { it.status in listOf(RunStatus.Waiting, RunStatus.Running) }) {
			throw ServiceException("Project is currently running and can't be deleted. Stop the all project runs before deleting the project.")
		}

		project.delete()
	}

	override suspend fun get(userId: String, projectId: String): ProjectData = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		val project = user.authProjectOrThrow(ProjectPermission.Read, userId, projectId)

		return project.toData(user)
	}

	override suspend fun newArgValues(userId: String, projectId: String, inData: CommonJobData.DataId, nodeId: String): ArgValuesToml = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Read, userId, projectId)

		// get the new job type
		val nodeConfig = NodeConfigs[nodeId]
			?: throw ServiceException("unrecognized nodeId: $nodeId")

		return nodeConfig.jobInfo.newArgValues(inData).toToml()
	}

	override suspend fun run(userId: String, projectId: String, jobIds: List<String>) = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		val project = user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the runner and wait for the result, so the jobs monitor populates with the run
		val runner = JobRunner(project)
		runner.init(jobIds)

		// then start the first job, but don't wait for the result so the UI stays responsive
		Backend.instance.scope.launch {
			runner.startFistJobIfIdle()
		}

		Unit
	}

	override suspend fun cancelRun(userId: String, projectId: String, runId: Int) = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		val project = user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		JobRunner(project).cancel(runId)
	}

	override suspend fun listRunning(userId: String): List<ProjectData> = sanitizeExceptions {

		// authenticate the user
		val user = call.authOrThrow()

		// get the projects with running jobs, that the user can read
		return Database.instance.projects.getAllRunningOrWaitingOwnedBy(userId) { docs ->
			docs
				.map { Project(it) }
				.filter { ProjectPermission.Read in user.permissions(it) }
				.map { it.toData(user) }
				.toList()
		}
	}

	override suspend fun getJobs(userId: String, projectId: String): List<String> = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Read, userId, projectId)

		return Job.allFromProject(userId, projectId)
			.map { it.data().serialize() }
	}

	override suspend fun positionJobs(positions: List<JobPosition>) = sanitizeExceptions {

		val user = call.authOrThrow()

		for (pos in positions) {

			// authenticate the user for this job
			user.authJobOrThrow(ProjectPermission.Write, pos.jobId)

			Job.savePosition(pos.jobId, pos.x, pos.y)
		}
	}

	override suspend fun deleteJobs(jobIds: List<String>) = sanitizeExceptions {

		val user = call.authOrThrow()

		for (jobId in jobIds) {

			// authenticate the user for this job
			val job = user.authJobOrThrow(ProjectPermission.Write, jobId)

			job.delete()
		}
	}

	override suspend fun latestRunId(jobId: String): Int = sanitizeExceptions {

		// authenticate the user for this job
		val user = call.authOrThrow()
		val job = user.authJobOrThrow(ProjectPermission.Read, jobId)
		val project = Project.getOrThrow(job)

		val projectRun = project.getRuns()
			.filter { it.jobs.any { jobRun -> jobRun.jobId == jobId } }
			.maxByOrNull { it.timestamp }
			?: throw ServiceException("Job has not been run yet")

		return projectRun.idOrThrow
	}

	override suspend fun clusterJobLog(clusterJobId: String): ClusterJobLog = sanitizeExceptions {

		// authenticate the user for this job
		val user = call.authOrThrow()
		val clusterJob = user.authClusterJobOrThrow(ProjectPermission.Read, clusterJobId)

		// load the main log
		val log = clusterJob.getLog()

		// load log failures
		val failedArrayIds = Database.instance.cluster.log.findArrayIdsByResultType(clusterJobId, ClusterJobResultType.Failure)
			.sorted()

		// load the template name, if possible
		val template = try {
			clusterJob.template?.let {
				Template.Key(it)
					.toTemplateOrThrow()
					.readData()
					.title
			}
		} catch (t: Throwable) {
			// can't read the template metadata, so use the path
			clusterJob.template
		}

		return ClusterJobLog(
			clusterJob.commands.representativeCommand(),
			clusterJob.commands.params(),
			log?.submitFailure,
			template,
			log?.launchScript,
			log?.launchResult?.toData(),
			log?.result?.type,
			log?.result?.exitCode,
			log?.result?.out?.collapseLog(),
			clusterJob.commands.arraySize,
			failedArrayIds
		)
	}

	override suspend fun clusterJobArrayLog(clusterJobId: String, arrayId: Int): ClusterJobArrayLog = sanitizeExceptions {

		// authenticate the user for this job
		val user = call.authOrThrow()
		val clusterJob = user.authClusterJobOrThrow(ProjectPermission.Read, clusterJobId)

		// load the array element log
		val log = clusterJob.getLog(arrayId)

		return ClusterJobArrayLog(log?.result?.type, log?.result?.exitCode, log?.result?.out?.collapseLog())
	}

	override suspend fun waitingReason(clusterJobId: String): String = sanitizeExceptions {

		// authenticate the user for this job
		val user = call.authOrThrow()
		val clusterJob = user.authClusterJobOrThrow(ProjectPermission.Read, clusterJobId)

		return Cluster.waitingReason(clusterJob)
	}

	override suspend fun renameJob(jobId: String, name: String): String = sanitizeExceptions {

		// authenticate the user for this job
		val user = call.authOrThrow()
		val job = user.authJobOrThrow(ProjectPermission.Write, jobId)

		// change the name
		val oldName = job.name
		job.name = name
		Job.saveName(jobId, name)

		LinkTree.jobRenamed(Project.getOrThrow(job), oldName, job)

		return job
			.data()
			.serialize()
	}

	override suspend fun wipeJob(jobId: String, deleteFilesAndData: Boolean): String = sanitizeExceptions {

		// authenticate the user for this job
		val user = call.authOrThrow()
		val job = user.authJobOrThrow(ProjectPermission.Write, jobId)

		if (deleteFilesAndData) {

			job.wipeData()
			job.deleteFiles()
			job.createFolder()

			// NOTE: wipeData() creates new args, which will put the block into a changed state
			//       so there's no longer a need to explicitly set the job as stale

		} else {

			// but if we don't wipe data, then we still need explicit staleness
			job.stale = true
			job.update()
		}

		job.data().serialize()
	}

	override suspend fun olderRuns(userId: String, projectId: String): List<ProjectRunData> = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		val project = user.authProjectOrThrow(ProjectPermission.Read, userId, projectId)

		val (olderRuns, _) = project.getRunsPartition()
		return olderRuns.map { it.toData() }
	}

	override suspend fun countSizes(userId: String, projectId: String): ProjectSizes = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		val project = user.authProjectOrThrow(ProjectPermission.Read, userId, projectId)

		// calculate the sizes
		ProjectSizes(
			filesystemBytes = DU.countBytes(project.dir)
		)
	}

	override suspend fun getSharedUsers(userId: String, projectId: String): List<UserData> = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		val project = user.authProjectOrThrow(ProjectPermission.Read, userId, projectId)

		project.readerUserIds
			.map { userId ->
				Database.instance.users.getUser(userId)
					?.toData()
					?: UserData(userId, null)
			}
	}

	override suspend fun setSharedUser(userId: String, projectId: String, sharedUserId: String) = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		Project.setReader(userId, projectId, sharedUserId)
	}

	override suspend fun unsetSharedUser(userId: String, projectId: String, sharedUserId: String) = sanitizeExceptions {

		// authenticate the user for this project
		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		Project.unsetReader(userId, projectId, sharedUserId)
	}

	override suspend fun workflows(): List<Workflow> = sanitizeExceptions {

		// authenticate the user
		call.authOrThrow()

		Workflows.workflows.values
			.toList()
			.sortedBy { it.name }
	}

	override suspend fun workflowArgs(workflowId: Int): String = sanitizeExceptions {

		// authenticate the user
		call.authOrThrow()

		val workflow = Workflows.workflows[workflowId]
			?: throw ServiceException("Workflow not found with id=$workflowId")

		Backend.instance.pypArgsWithMicromon
			.filterArgs(workflow.blocks.flatMap { it.askArgs })
			.toJson()
	}
}
