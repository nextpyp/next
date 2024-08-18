package edu.duke.bartesaghi.micromon.nodes


object TomographyPickingClosedNodeConfig : NodeConfig {

	const val ID = "tomo-picking-closed"

	override val id = ID
	override val configId = "tomo_segment_close"
	override val name = "Particle-Picking (closed surfaces)"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val inMovieRefinement = NodeConfig.Data("inMovieRefinement", NodeConfig.Data.Type.Tomograms)
	val outMovieRefinement = NodeConfig.Data("outMovieRefinement", NodeConfig.Data.Type.MovieRefinement)

	override val inputs = listOf(inMovieRefinement)
	override val outputs = listOf(outMovieRefinement)
}
