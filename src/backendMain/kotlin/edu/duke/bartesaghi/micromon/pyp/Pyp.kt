package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.Config
import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.CommandsScript
import edu.duke.bartesaghi.micromon.cluster.Container
import edu.duke.bartesaghi.micromon.linux.Posix
import edu.duke.bartesaghi.micromon.mongo.Database
import java.nio.file.Path


/**
 * An interface between the web server and PYP
 */
// the linters wants us to capitalize the command names,
// but it's probably better to match the actual command names instead
@Suppress("EnumEntryName")
enum class Pyp(private val cmdName: String) {

	gyp("gyp"),
	rlp("rlp"),
	pyp("pyp"),
	csp("csp"),
	pcl("pcl"),
	pmk("pmk"),
	psp("psp"),
	pex("pex"),
	webrpc("webrpc"),
	streampyp("streampyp");

	fun launch(
		/** The user for whom properties will be fetched for the cluster job launch, if any */
		userId: String?,
		/**
		 * The OS username to use for filesystem operations.
		 * WARNRING: The OS username must match the one for project whose job is being launched.
		 *           That is not necessarily the same as the OS username of user launching this job.
		 */
		osUsername: String?,
		/** a user-friendly name for the job to display on the website */
		webName: String,
		/** a user-friendly name for the job to display in cluster management tools */
		clusterName: String,
		/** the owner id of the SLURM job and all sub jobs */
		owner: String,
		/** the listner to call when the cluster job is finished */
		ownerListener: ClusterJob.OwnerListener?,
		/** path to the working directory */
		dir: Path,
		/** arguments for the executable passed via the command-line. All paths must be in the internal (to the container) file system */
		args: List<String>,
		/** parameters for the executable passed via the parameters file, if any. All paths must be in the internal (to the container) file system */
		params: String? = null,
		/** arguments for when submitting the initial job to cluster, eg sbatch */
		launchArgs: List<String> = emptyList(),
		/** the template to use for this job, if any */
		template: String? = null
	) : ClusterJob {

		// launch a PYP script inside of the container via SLURM
		val (containerId, runCmd) =
			if (Config.instance.pyp.mock != null) {
				// or mock pyp entirely, if configured for testing
				Container.MockPyp.id to "RUST_BACKTRACE=1 ${Container.MockPyp.exec} $cmdName"
			} else {
				Container.Pyp.id to Container.Pyp.run(cmdName)
			}

		val clusterJob = ClusterJob(
			osUsername = osUsername,
			userProperties = userId?.let { Database.instance.users.getProperties(it) },
			containerId = containerId,
			commands = CommandsScript(
				commands = listOf(
					runCmd + " " + args.joinToString(" ", transform=Posix::quote)
				),
				params = params
			),
			dir = dir,
			args = launchArgs,
			ownerId = owner,
			ownerListener = ownerListener,
			webName = webName,
			clusterName = clusterName,
			type = "launch",
			template = template
		)

		clusterJob.submit()
			?: Backend.log.debug("Cluster job canceled before launching: [\"$webName\", \"$clusterName\"]")

		return clusterJob
	}

	companion object {

		suspend fun cancel(ownerId: String): Cluster.CancelResult =
			Cluster.cancelAll(ownerId)
	}
}
