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
		Backend.instance.pypArgsWithMicromon
			.filter(config.configId)

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

			// but only look at the groups for this block, not args from all groups
			val block = Backend.instance.pypArgs.block(job.baseConfig.configId)
			if (block == null) {
				debugMsg?.append("""
					|
					|  ${job.baseConfig.id}=${job.id}
					|    Skipping ${jobValues.entries.size} arg value(s), block not found
					|    configId = '${job.baseConfig.configId}' not found
					|    among: ${values.args.blocks.map { it.blockId }}
				""".trimMargin())
				continue
			}
			for (groupId in block.groupIds) {
				for (arg in values.args.args(groupId)) {

					// skip uncopyable args
					if (!arg.copyToNewBlock) {
						continue
					}

					// copy the value, but remove any explicit defaults
					// NOTE: this allows default values to override upstream values by removing them
					values[arg] = jobValues[arg]
						?.takeIf { arg.default == null || it != arg.default.value }
				}
			}

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
