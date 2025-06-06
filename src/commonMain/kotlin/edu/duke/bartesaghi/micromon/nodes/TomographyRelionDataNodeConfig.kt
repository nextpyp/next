package edu.duke.bartesaghi.micromon.nodes

import edu.duke.bartesaghi.micromon.nodes.NodeConfig.NodeType


object TomographyRelionDataNodeConfig : NodeConfig {

	const val ID = "tomo-reliondata"

	override val id = ID
	override val configId = "tomo_import_star"
	override val name = "Tomography (from Star)"
	override val hasFiles = true
	override val type = NodeType.TomographyRawData

	val movieRefinement = NodeConfig.Data("movie-refinement", NodeConfig.Data.Type.MovieRefinement)

	override val inputs = emptyList<NodeConfig.Data>()
	override val outputs = listOf(movieRefinement)
}
