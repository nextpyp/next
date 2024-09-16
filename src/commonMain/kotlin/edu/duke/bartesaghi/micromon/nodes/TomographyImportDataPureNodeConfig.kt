package edu.duke.bartesaghi.micromon.nodes

import edu.duke.bartesaghi.micromon.nodes.NodeConfig.NodeType


object TomographyImportDataPureNodeConfig : NodeConfig {

	const val ID = "tomo-import-pure"

	override val id = ID
	override val configId = "tomo_import_pure"
	override val name = "Tomography (from Project)"
	override val hasFiles = true
	override val type = NodeType.TomographyRawData

	val tomograms = NodeConfig.Data("tomograms", NodeConfig.Data.Type.Tomograms)

	override val inputs = emptyList<NodeConfig.Data>()
	override val outputs = listOf(tomograms)
}
