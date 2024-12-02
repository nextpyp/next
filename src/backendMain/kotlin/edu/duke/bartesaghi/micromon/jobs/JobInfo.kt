package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.CommonJobData
import edu.duke.bartesaghi.micromon.services.JobData
import org.bson.Document
import kotlin.reflect.KClass


interface JobInfo {

	val config: NodeConfig
	val dataType: DataType?
	val dataClass: KClass<out JobData>?

	fun fromDoc(doc: Document): Job

	enum class DataType {
		Micrograph,
		TiltSeries
	}

	fun args() =
		Backend.instance.pypArgs
			.filter(config.configId)
			.appendAll(MicromonArgs.slurmLaunch)

	fun newArgValues(inData: CommonJobData.DataId): ArgValues {

		val debugMsg =
			if (Backend.log.isDebugEnabled) {
				StringBuilder().apply {
					append("newArgValues()  nodeId=${config.id}")
				}
			} else {
				null
			}

		// get the args to copy for this block
		val values = ArgValues(args())

		// get the upstream job
		val upstreamJob = Job.fromIdOrThrow(inData.jobId)

		// iterate jobs in stream order, so downstream jobs overwrite upstream jobs
		for (job in upstreamJob.ancestry().reversed()) {

			// combine values from the job
			val jobValues = (job.newestArgValues() ?: "")
				.toArgValues(values.args)
			for ((arg, value) in jobValues.entries) {

				val remove =
					// remove args whose value is the default value
					(arg.default != null && value == arg.default.value)
					// remove uncopyable args
					|| !arg.copyToNewBlock

				if (remove) {
					jobValues[arg] = null
				}
			}

			// merge the remaining args
			values.setAll(jobValues)

			if (Backend.log.isDebugEnabled) {
				debugMsg?.append("""
					|
					|  ${job.baseConfig.id}=${job.id}
					|    ${jobValues.toString().lines().joinToString("\n    ")}
				""".trimMargin())
			}
		}

		if (Backend.log.isDebugEnabled) {
			Backend.log.debug(debugMsg?.toString())
		}

		return values
	}
}
