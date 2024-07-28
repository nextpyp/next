package edu.duke.bartesaghi.micromon.nodes


object TomographyPickingOpenNodeConfig : NodeConfig {

	const val ID = "tomo-picking-open"

	override val id = ID
	override val configId = "tomo_segment_open"
	override val name = "Particle-Picking on Open Surface"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val tomograms = NodeConfig.Data("tomograms", NodeConfig.Data.Type.Tomograms)
	val movieRefinement = NodeConfig.Data("movieRefinement", NodeConfig.Data.Type.MovieRefinement)

	override val inputs = listOf(tomograms)
	override val outputs = listOf(movieRefinement)
}
