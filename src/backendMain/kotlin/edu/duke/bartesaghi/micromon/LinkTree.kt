package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.linux.userprocessor.*
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.projects.Project
import edu.duke.bartesaghi.micromon.sessions.Session
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.moveTo


/**
 * Maintains the tree of links, which is a human-readable collection of filesystem symlinks
 * to the machine-readable folders maintained by the website.
 *
 * Since the machine-readable folder names are expected to be unique and remain immutable after creation,
 * and the human-readable names are expected to match the current names of database documents
 * (which can be renamed and collide with each other ad nauseum),
 * the human-readable names are implemented as symlinks to the machine-readable folders,
 * so the symlinks can be updated whenever the database document names change without worrying about
 * breaking the machine-readability of the filesystem or ever actually moving files around.
 *
 * The two main parts of the filesystem represented by the link tree are:
 *  1. The user project folders, with nested blocks, at:
 *       $shared/users/$userId/project-links/P$projectNumber-$projectName/B$jobNumber-$jobName
 *  2. The group/session folders at:
 *       $shared/groups/$groupName/S$sessionNumber-$sessionName
 */
object LinkTree {

	private val usersDir = Backend.config.web.sharedDir / "users"
	private val groupsDir = Backend.config.web.sharedDir / "groups"


	private fun userDir(userId: String): Path =
		usersDir / userId

	private fun userProjectLinksDir(userId: String): Path =
		userDir(userId) / "project-links"

	private fun projectFilename(number: Int, name: String): String =
		"P$number-$name".sanitizeFileName()

	private fun projectPath(userId: String, number: Int, name: String): Path =
		userProjectLinksDir(userId) / projectFilename(number, name)

	suspend fun projectCreated(project: Project) {

		// new projects should always have a project number
		val projectNumber = project.projectNumber
			?: throw NoSuchElementException("new project has no project number")
		val projectPath = projectPath(project.userId, projectNumber, project.name)

		// get the owner for this project
		val user = Database.users.getUser(project.userId)

		// make the filesystem change
		projectPath.createDirsIfNeededAs(user?.osUsername)
	}

	// NOTE: currently, projects can't be renamed, so we don't need this just yet
	suspend fun projectRenamed(oldName: String, project: Project) {

		// projects without numbers won't have links, so there's nothing to rename
		val projectNumber = project.projectNumber
			?: return
		val oldPath = projectPath(project.userId, projectNumber, oldName)
		val newFilename = projectFilename(projectNumber, project.name)

		// get the owner for this project
		val user = Database.users.getUser(project.userId)

		// make the filesystem change
		oldPath.renameAs(user?.osUsername, newFilename)
	}

	suspend fun projectDeleted(project: Project) {

		// projects without numbers won't have links, so there's nothing to delete
		val projectNumber = project.projectNumber
			?: return
		val projectPath = projectPath(project.userId, projectNumber, project.name)

		// get the owner for this project
		val user = Database.users.getUser(project.userId)

		// make the filesystem change
		projectPath.deleteDirRecursivelyAs(user?.osUsername)
	}

	private fun jobFilename(number: Int, name: String): String =
		"B$number-$name".sanitizeFileName()

	suspend fun jobCreated(project: Project, job: Job) {

		// old project may not have a project number, so we can't create job links either
		val projectNumber = project.projectNumber
			?: return
		val projectPath = projectPath(project.userId, projectNumber, project.name)

		// new jobs should always have a project number though
		val jobNumber = job.jobNumber
			?: throw NoSuchElementException("new job has no job number")
		val jobPath = projectPath / jobFilename(jobNumber, job.name)

		// get the owner for this project
		val user = Database.users.getUser(project.userId)

		// make the filesystem change
		job.dir.symlinkAs(user?.osUsername, jobPath)
	}

	suspend fun jobRenamed(project: Project, oldName: String, job: Job) {

		// old project may not have a project number, so we can't create job links either
		val projectNumber = project.projectNumber
			?: return
		val projectPath = projectPath(project.userId, projectNumber, project.name)

		// old job might not have a job number either
		val jobNumber = job.jobNumber
			?: return
		val oldPath = projectPath / jobFilename(jobNumber, oldName)
		val newFilename = jobFilename(jobNumber, job.name)

		// get the owner for this project
		val user = Database.users.getUser(project.userId)

		// make the filesystem change
		oldPath.renameAs(user?.osUsername, newFilename)
	}

	suspend fun jobDeleted(project: Project, job: Job) {

		// old project may not have a project number, so we can't create job links either
		val projectNumber = project.projectNumber
			?: return
		val projectPath = projectPath(project.userId, projectNumber, project.name)

		// old job might not have a job number either
		val jobNumber = job.jobNumber
			?: return
		val jobPath = projectPath / jobFilename(jobNumber, job.name)

		// get the owner for this project
		val user = Database.users.getUser(project.userId)

		// make the filesystem change
		jobPath.deleteAs(user?.osUsername)
	}

	private fun groupFilename(name: String): String =
		name.sanitizeFileName()

	private fun groupPath(name: String): Path =
		groupsDir / groupFilename(name)

	private fun sessionFilename(number: Int, name: String): String =
		"S$number-$name".sanitizeFileName()

	fun groupRenamed(oldName: String, group: Group) {

		val oldPath = groupPath(oldName)
		val newFilename = groupFilename(group.name)

		// make the filesystem change, if needed
		if (oldPath.exists()) {
			oldPath.rename(newFilename)
		}
	}

	fun groupDeleted(group: Group) {

		val groupPath = groupPath(group.name)

		// make the filesystem change
		if (groupPath.exists()) {
			groupPath.deleteDirRecursively()
		}
	}

	fun sessionCreated(session: Session, group: Group) {

		// new sessions should always have a session number
		val sessionNumber = session.sessionNumber
			?: throw NoSuchElementException("new session has no session number")
		val sessionFilename = sessionFilename(sessionNumber, session.newestArgs().name)
		val newPath = groupPath(group.name) / sessionFilename

		// make the filesystem change
		session.dir.symlink(newPath)
	}

	fun sessionEdited(oldGroup: Group?, oldName: String, session: Session, newGroup: Group) {

		// old sessions won't have session numbers, so there's nothing to do
		val sessionNumber = session.sessionNumber
			?: return
		val oldPath = oldGroup?.let {
			groupPath(it.name) / sessionFilename(sessionNumber, oldName)
		}
		val newPath = groupPath(newGroup.name) / sessionFilename(sessionNumber, session.newestArgs().name)

		// make the filesystem change
		if (oldPath != null) {
			newPath.parent.createDirsIfNeeded()
			oldPath.moveTo(newPath)
		} else {
			session.dir.symlink(newPath)
		}
	}

	fun sessionDeleted(session: Session, group: Group) {

		// old sessions won't have session numbers, so there's nothing to do
		val sessionNumber = session.sessionNumber
			?: return
		val path = groupPath(group.name) / sessionFilename(sessionNumber, session.newestArgs().name)

		// make the filesystem change
		path.deleteIfExists()
	}
}
