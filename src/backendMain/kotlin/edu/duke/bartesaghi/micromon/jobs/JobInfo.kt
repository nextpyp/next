package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import org.bson.Document


interface JobInfo {

	val config: NodeConfig
	val dataType: DataType?

	fun fromDoc(doc: Document): Job

	enum class DataType {
		Micrograph,
		TiltSeries
	}
}
