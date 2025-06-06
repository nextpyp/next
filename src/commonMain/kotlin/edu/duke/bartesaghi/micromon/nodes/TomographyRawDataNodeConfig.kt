package edu.duke.bartesaghi.micromon.nodes

import edu.duke.bartesaghi.micromon.nodes.NodeConfig.NodeType


object TomographyRawDataNodeConfig : NodeConfig {

	const val ID = "tomo-rawdata"

	override val id = ID
	override val configId = "tomo_import_raw"
	override val name = "Tomography (from Raw Data)"
	override val hasFiles = true
	override val type = NodeType.TomographyRawData

	val tiltSeries = NodeConfig.Data("tilt-series", NodeConfig.Data.Type.TiltSeries)

	override val inputs = emptyList<NodeConfig.Data>()
	override val outputs = listOf(tiltSeries)
}
