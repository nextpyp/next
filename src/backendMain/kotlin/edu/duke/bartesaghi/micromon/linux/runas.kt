package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.*
import java.nio.file.Path
import kotlin.io.path.div


sealed interface Runas {

	companion object {

		suspend fun find(username: String): Runas = slowIOs f@{

			val dir = Backend.config.web.runasDir
			val path = (dir / "runas-$username")

			// find the uid
			val uid = Backend.hostProcessor.uid(username)
				?: return@f Failure(path, listOf("Unknown username: $username"))

			// find the user-specific runas executable
			if (!path.exists()) {
				return@f Failure(path, listOf("File not found: $path"))
			}

			// check the unix permissions
			val stat = Filesystem.stat(path)
			val failures = ArrayList<String>()

			// the file should be owned by the given username
			if (stat.uid != uid) {
				val fileUsername = Backend.hostProcessor.username(stat.uid)
				failures.add("File permissions: Should be owned by $username, not $fileUsername")
			}

			// owner should have: setuid
			if (!stat.isSetUID) {
				failures.add("File permissions: Should be setuid")
			}

			// group should have: r-x
			if (!stat.isGroupRead) {
				failures.add("File permissions: Group should read")
			}
			if (stat.isGroupWrite) {
				failures.add("File permissions: Group must not write")
			}
			if (!stat.isGroupExecute) {
				failures.add("File permissions: Group should execute")
			}

			// other should have: r-- or ---
			if (stat.isOtherWrite) {
				failures.add("File permissions: Others must not write")
			}
			if (stat.isOtherExecute) {
				failures.add("File permissions: Others must not execute")
			}

			// and the file should be owned by any group among this user's groups
			val websiteUid = Filesystem.getUid()
			val websiteGids = Backend.hostProcessor.gids(websiteUid)
			if (websiteGids == null || stat.gid !in websiteGids) {
				val micromonUsername = Backend.hostProcessor.username(websiteUid)
				val fileGroupname = Backend.hostProcessor.groupname(stat.gid)
				failures.add("File permissions: website user $micromonUsername is not a member of group $fileGroupname")
			}

			if (failures.isNotEmpty()) {
				return@f Failure(path, failures)
			}

			return@f Success(path)
		}
	}

	class Success(
		val path: Path
	) : Runas {

		private fun args(cmd: String, args: List<String>): List<String> =
			listOf("--", cmd) + args

		suspend fun exec(
			cmd: String,
			args: List<String> = emptyList(),
			dir: Path? = null
		): HostProcessor.Process =
			Backend.hostProcessor.exec(
				path.toString(),
				args(cmd, args),
				dir
			)

		suspend fun execStream(
			cmd: String,
			args: List<String> = emptyList(),
			dir: Path? = null,
			stdin: Boolean = false
		): HostProcessor.StreamingProcess =
			Backend.hostProcessor.execStream(
				path.toString(),
				args(cmd, args),
				dir,
				stdin = stdin,
				stdout = true,
				stderr = true
			)

		/* TODO
		fun jvm(): RunasJvm {
			// TODO
		}
		*/
	}

	class Failure(
		val path: Path,
		val reasons: List<String>
	) : Runas
}
