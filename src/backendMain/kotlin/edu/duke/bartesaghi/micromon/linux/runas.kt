package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.Config
import edu.duke.bartesaghi.micromon.exists
import edu.duke.bartesaghi.micromon.linux.hostprocessor.HostProcessor
import edu.duke.bartesaghi.micromon.slowIOs
import java.nio.file.Path
import kotlin.io.path.div


sealed interface Runas {

	companion object {

		suspend fun find(username: String, hostProcessor: HostProcessor): Runas = slowIOs f@{

			val dir = Config.instance.web.runasDir
			val path = (dir / "runas-$username")

			// find the uid
			val uid = hostProcessor.uid(username)
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
				val fileUsername = hostProcessor.username(stat.uid)
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
			val websiteGids = hostProcessor.gids(websiteUid)
			if (websiteGids == null || stat.gid !in websiteGids) {
				val micromonUsername = hostProcessor.username(websiteUid)
				val fileGroupname = hostProcessor.groupname(stat.gid)
				failures.add("File permissions: website user $micromonUsername is not a member of group $fileGroupname")
			}

			if (failures.isNotEmpty()) {
				return@f Failure(path, failures)
			}

			return@f Success(username, path)
		}

		suspend fun findOrThrow(username: String, hostProcessor: HostProcessor): Success =
			when (val runas = find(username, hostProcessor)) {
				is Success -> runas
				is Failure -> throw RunasException(runas)
			}
	}

	class Success(
		val username: String,
		val path: Path
	) : Runas {

		fun wrap(cmd: Command): Command =
			cmd.wrap(path.toString(), listOf("--"))
	}

	class Failure(
		val path: Path,
		val reasons: List<String>
	) : Runas
}


class RunasException(failure: Runas.Failure) : RuntimeException("""
		|Failed to use runas executable at: ${failure.path}
		|	${failure.reasons.joinToString("\n\t")}
	""".trimMargin())
