package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.Backend
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
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

	fun args(includeForwarded: Boolean = false) =
		Backend.instance.pypArgs
			.filter(config.configId, includeForwarded)
			.appendAll(MicromonArgs.slurmLaunch)
}
