package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import org.bson.Document


interface JobInfo {

	val config: NodeConfig
	val dataType: DataType?

	fun fromDoc(doc: Document): Job

	enum class DataType {
		Micrograph,
		TiltSeries
	}

	fun args(includeForwarded: Boolean = false) =
		Backend.instance.pypArgs
			.filter(config.configId, includeForwarded)
			.appendAll(MicromonArgs.slurmLaunch)

	fun launchArgValues(upstreamJob: Job?, currentValues: ArgValuesToml, prevValues: ArgValuesToml?): ArgValues {

		val values = ArgValues(Backend.instance.pypArgs)

		// explicitly set the block id
		values.micromonBlock = config.id

		// set the current job arg values
		values.setAll(args().diff(currentValues, prevValues))

		Backend.log.debug("""
			|launchArgValues()  job=${config.id}
			|  current:  ${currentValues.lines().filter { it.isNotBlank() }.joinToString("\n            ")}
			|     prev:  ${prevValues?.lines()?.filter { it.isNotBlank() }?.joinToString("\n            ") ?: "(none)"}
			|     diff:  ${values.toString().lines().joinToString("\n            ")}
		""".trimMargin())

		// forward upstream tabs, if needed
		if (upstreamJob != null) {

			val upstreamValues = upstreamJob.finishedArgValues()
				?.toArgValues(values.args)
				?: throw IllegalStateException("upstream job has no finished args")

			val forwardedValues = upstreamValues.project(Args.from(
				Backend.instance.pypArgs
					.blockOrThrow(config.configId)
					.forwardedGroupIds
					.flatMap { values.args.args(it) }
					.filter { !(it.default != null && upstreamValues[it] == it.default.value) } // don't forward any default values
			))

			Backend.log.debug("""
				|launchArgValues()  upstream=${upstreamJob.baseConfig.id}
				|  forwarded:  ${forwardedValues.toString().lines().joinToString("\n              ")}
			""".trimMargin())

			values.setAll(forwardedValues)
		}

		return values
	}
}
