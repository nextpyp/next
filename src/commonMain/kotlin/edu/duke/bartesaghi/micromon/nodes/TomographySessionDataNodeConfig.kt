package edu.duke.bartesaghi.micromon.nodes

import edu.duke.bartesaghi.micromon.nodes.NodeConfig.NodeType


object TomographySessionDataNodeConfig : NodeConfig {

	const val ID = "tomo-session"

	override val id = ID
	override val configId = "tomo_pre_process"
	override val name = "Tomography (from Session)"
	override val hasFiles = true
	override val type = NodeType.TomographyRawData

	val movieRefinement = NodeConfig.Data("movie-refinement", NodeConfig.Data.Type.MovieRefinement)

	override val inputs = emptyList<NodeConfig.Data>()
	override val outputs = listOf(movieRefinement)
}
