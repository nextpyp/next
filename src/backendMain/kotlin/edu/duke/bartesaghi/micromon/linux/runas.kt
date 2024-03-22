package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.exists
import edu.duke.bartesaghi.micromon.toPath
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.div


sealed interface Runas {

	companion object {

		suspend fun find(username: String): Runas {

			// find the user-specific runas executable
			val dir = Backend.config.web.runasDir
			val path = (dir / "runas-$username")
			if (!path.exists()) {
				return Failure(listOf("runas executable not found at: $path"))
			}

			// check the unix permissions
			val stat = Filesystem.stat(path)
			val failures = ArrayList<String>()

			// the file should be owned by the given username
			val ownerUsername = stat.username()
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

			// and the file should be owned by group among this user's groups
			if (!Filesystem.groupMember(stat.gid)) {
				failures.add("File permissions: website user $ is not a member of group ${stat.groupname()}")
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

	class Failure(val reasons: List<String>) : Runas
}


/* DEBUG
fun main() {

	runBlocking {
		val stat = Filesystem.stat("/home/jeff/archbtw".toPath())
		println("stat: $stat")
		println("username: ${stat.username()}")
		println("groupname: ${stat.groupname()}")
	}
}
*/
