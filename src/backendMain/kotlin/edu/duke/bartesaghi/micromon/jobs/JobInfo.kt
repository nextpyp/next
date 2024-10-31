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

		val args = Backend.instance.pypArgs
		val values = ArgValues(args)

		// explicitly set the block id
		values.micromonBlock = config.id

		// forward upstream tabs, if needed
		if (upstreamJob != null) {
			val upstreamValues = upstreamJob.finishedArgValues()
				?.toArgValues(Backend.instance.pypArgs)
				?: throw IllegalStateException("upstream job has no finished args")
			Backend.instance.pypArgs
				.blockOrThrow(config.configId)
				.forwardedGroupIds
				.flatMap { Backend.instance.pypArgs.args(it) }
				.filter { !values.contains(it) } // but don't overwrite any values already there
				.forEach { values[it] = upstreamValues[it] }
		}

		// set the current job arg values
		values.setAll(args().diff(currentValues, prevValues))

		return values
	}
}
