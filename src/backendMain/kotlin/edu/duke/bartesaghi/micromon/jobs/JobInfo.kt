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
		Backend.pypArgs
			.filter(config.configId, includeForwarded)
			.appendAll(MicromonArgs.slurmLaunch)

	fun launchArgValues(upstreamJob: Job?, currentValues: ArgValuesToml, prevValues: ArgValuesToml?): ArgValues {

		val args = Backend.pypArgs
			.appendAll(MicromonArgsForPyp.all)
		val values = ArgValues(args)

		// explicitly set the block id
		values.micromonBlock = config.id

		// forward upstream tabs, if needed
		if (upstreamJob != null) {
			val upstreamValues = upstreamJob.finishedArgValues()
				?.toArgValues(Backend.pypArgs)
				?: throw IllegalStateException("upstream job has no finished args")
			Backend.pypArgs
				.blockOrThrow(config.configId)
				.forwardedGroupIds
				.flatMap { Backend.pypArgs.args(it) }
				.forEach { values[it] = upstreamValues[it] }
		}

		// set the current job arg values
		values.setAll(args().diff(currentValues, prevValues))

		return values
	}
}
