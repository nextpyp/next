package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValues
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.MicromonArgs
import edu.duke.bartesaghi.micromon.pyp.toArgValues
import org.bson.Document


interface JobInfo {

	val config: NodeConfig
	val dataType: DataType?

	fun fromDoc(doc: Document): Job

	enum class DataType {
		Micrograph,
		TiltSeries
	}

	fun args() =
		Backend.pypArgs
			.filter(config.configId)
			.appendAll(MicromonArgs.slurmLaunch)

	fun launchArgValues(upstreamJob: Job?, currentValues: ArgValuesToml, prevValues: ArgValuesToml?): ArgValues {

		val values = ArgValues(Backend.pypArgs)

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
