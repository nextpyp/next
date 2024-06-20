package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.pyp.MicromonArgs
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
			.filter(config.configId, includeHiddenArgs = false, includeHiddenGroups = true)
			.appendAll(MicromonArgs.slurmLaunch)
}
