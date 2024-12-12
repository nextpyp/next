package edu.duke.bartesaghi.micromon.cluster.slurm

import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.Config
import edu.duke.bartesaghi.micromon.SSHPool
import edu.duke.bartesaghi.micromon.linux.Command


class SQueue(
	val sshPool: SSHPool,
	val config: Config.Slurm,
	val osUsername: String? = null
) {

	/**
	 * SLURM squeue formats:
	 * https://slurm.schedmd.com/squeue.html#OPT_format
	 */
	private suspend fun call(jobId: Long, format: String): String? {

		var cmd = Command(config.cmdSqueue,
			"-j", jobId.toString(),
			"-h", "--format=$format"
		)

		// run the job as the specified OS user, if needed
		if (osUsername != null) {
			cmd = Backend.instance.userProcessors.get(osUsername).wrap(cmd, quiet=true)
		}

		val lines = sshPool.connection {
			exec(cmd.toShellSafeString())
				.console
		}

		// get the result, or return null to signal no result
		return lines.firstOrNull()
			?.takeIf { it != "slurm_load_jobs error: Invalid job id specified" }
	}

	suspend fun isActive(jobId: Long): Boolean {

		// call squeue, should get something like:
		//RUNNING,None
		// or empty output if job isn't running or scheduled
		val out = call(jobId, "%T,%r")

		// if we got useful output, it's probably info about the job, right?
		return out != null
	}

	suspend fun reason(jobId: Long): JobReason? {

		val out = call(jobId, "%r")
			?: return null

		// the output should just be a job reason code
		return JobReason[out]
	}


	/**
	 * https://slurm.schedmd.com/squeue.html#SECTION_JOB-REASON-CODES
	 */
	enum class JobReason(val description: String) {

		AssociationJobLimit("The job's association has reached its maximum job count."),
		AssociationResourceLimit("The job's association has reached some resource limit."),
		AssociationTimeLimit("The job's association has reached its time limit."),
		BadConstraints("The job's constraints can not be satisfied."),
		BeginTime("The job's earliest start time has not yet been reached."),
		Cleaning("The job is being requeued and still cleaning up from its previous execution."),
		Dependency("This job is waiting for a dependent job to complete."),
		FrontEndDown("No front end node is available to execute this job."),
		InactiveLimit("The job reached the system InactiveLimit."),
		InvalidAccount("The job's account is invalid."),
		InvalidQOS("The job's QOS is invalid."),
		JobHeldAdmin("The job is held by a system administrator."),
		JobHeldUser("The job is held by the user."),
		JobLaunchFailure("The job could not be launched. This may be due to a file system problem, invalid program name, etc."),
		Licenses("The job is waiting for a license."),
		NodeDown("A node required by the job is down."),
		NonZeroExitCode("The job terminated with a non-zero exit code."),
		PartitionDown("The partition required by this job is in a DOWN state."),
		PartitionInactive("The partition required by this job is in an Inactive state and not able to start jobs."),
		PartitionNodeLimit("The number of nodes required by this job is outside of its partition's current limits. Can also indicate that required nodes are DOWN or DRAINED."),
		PartitionTimeLimit("The job's time limit exceeds its partition's current time limit."),
		Priority("One or more higher priority jobs exist for this partition or advanced reservation."),
		Prolog("Its PrologSlurmctld program is still running."),
		QOSJobLimit("The job's QOS has reached its maximum job count."),
		QOSResourceLimit("The job's QOS has reached some resource limit."),
		QOSTimeLimit("The job's QOS has reached its time limit."),
		ReqNodeNotAvail("Some node specifically required by the job is not currently available. The node may currently be in use, reserved for another job, in an advanced reservation, DOWN, DRAINED, or not responding. Nodes which are DOWN, DRAINED, or not responding will be identified as part of the job's \"reason\" field as \"UnavailableNodes\". Such nodes will typically require the intervention of a system administrator to make available."),
		Reservation("The job is waiting its advanced reservation to become available."),
		Resources("The job is waiting for resources to become available."),
		SystemFailure("Failure of the Slurm system, a file system, the network, etc."),
		TimeLimit("The job exhausted its time limit."),
		QOSUsageThreshold("Required QOS threshold has been breached."),
		WaitingForScheduling("No reason has been set for this job yet. Waiting for the scheduler to determine the appropriate reason.");


		companion object {

			operator fun get(name: String): JobReason? =
				values().find { it.name == name }
		}
	}
}
