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
			val uid = HostProcessorOld.uid(username)
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
				val fileUsername = HostProcessorOld.tryUsername(stat.uid)
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
			val websiteGids = HostProcessorOld.gids(websiteUid)
			if (websiteGids == null || stat.gid !in websiteGids) {
				val micromonUsername = HostProcessorOld.tryUsername(websiteUid)
				val fileGroupname = HostProcessorOld.tryGroupname(stat.gid)
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

		fun cmd(cmd: String, args: List<String> = emptyList()): ProcessStreamer =
			ProcessBuilder()
				.command(listOf(path.toString(), "--", cmd) + args)
				.stream()
				.waitFor()

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
