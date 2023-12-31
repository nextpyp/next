package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.cluster.Cluster
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.CommandsScript
import edu.duke.bartesaghi.micromon.cluster.Container
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
		/** arguments for the PYP script. All paths must be in the internal (to the container) file system */
		args: List<String>,
		/** arguments for when submitting the initial job to cluster, eg sbatch */
		launchArgs: List<String> = emptyList()
	) : ClusterJob {

		// launch a PYP script inside of the container via SLURM
		val clusterJob = ClusterJob(
			containerId = Container.Pyp.id,
			commands = CommandsScript(commands = listOf(
				Container.Pyp.run(cmdName) + " " + args.joinToString(" ")
			)),
			dir = dir,
			args = launchArgs,
			ownerId = owner,
			ownerListener = ownerListener,
			webName = webName,
			clusterName = clusterName
		)

		clusterJob.submit()

		return clusterJob
	}

	companion object {

		suspend fun cancel(ownerId: String): Cluster.CancelResult =
			Cluster.cancelAll(ownerId)
	}
}
