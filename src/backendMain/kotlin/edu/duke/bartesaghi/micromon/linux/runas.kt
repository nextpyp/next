package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.exists
import java.nio.file.Path
import kotlin.io.path.div


sealed interface Runas {

	companion object {

		suspend fun find(username: String): Runas {

			// find the user-specific runas executable
			val dir = Backend.config.web.runasDir
			val path = (dir / "runas-$username")
			if (!path.exists()) {
				return Failure(path, listOf("runas executable not found at: $path"))
			}

			// TODO: update host processor to handle user,group queries

			// check the unix permissions
			val stat = Filesystem.stat(path)
			val failures = ArrayList<String>()

			/* TEMP

			// the file should be owned by the given username
			val ownerUsername = stat.username()
				?: stat.uid.toString()
			if (ownerUsername != username) {
				failures.add("not owned by $username, owned by $ownerUsername")
			}

			// owner should have: setuid
			if (!stat.isSetUID) {
				failures.add("File permissions: Not setuid")
			}

			// group should have: r-x
			if (!stat.isGroupRead) {
				failures.add("File permissions: Group can't read")
			}
			if (stat.isGroupWrite) {
				failures.add("File permissions: Group can write")
			}
			if (!stat.isGroupExecute) {
				failures.add("File permissions: Group can't execute")
			}

			// other should have: r-- or ---
			if (stat.isOtherWrite) {
				failures.add("File permissions: Other can write")
			}
			if (stat.isOtherExecute) {
				failures.add("File permissions: Other can execute")
			}

			// and the file should be owned by any group among this user's groups
			if (!Filesystem.groupMember(stat.gid)) {
				val websiteUsername: String = try {
					val uid = Filesystem.getUid()
					Filesystem.getUsername(uid)
						?: uid.toString()
				} catch (t: Throwable) {
					Backend.log.error("Failed to get website username", t)
					"(unknown)"
				}
				val fileGroupname: String = stat.groupname()
					?: stat.gid.toString()
				failures.add("File permissions: website user $websiteUsername is not a member of group $fileGroupname")
			}

			*/

			if (failures.isNotEmpty()) {
				return Failure(path, failures)
			}

			return Success(path)
		}
	}

	class Success(
		val path: Path
	) : Runas {

		// TODO: some kind of run function?
		//   a function to start a child jvm process?
	}

	class Failure(
		val path: Path,
		val reasons: List<String>
	) : Runas
}
