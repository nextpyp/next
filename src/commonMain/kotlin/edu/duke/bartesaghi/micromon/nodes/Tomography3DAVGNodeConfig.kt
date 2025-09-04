package edu.duke.bartesaghi.micromon.nodes


object Tomography3DAVGNodeConfig : NodeConfig {

	const val ID = "tomo-sva"

	override val id = ID
	override val configId = "tomo_sva_refine"
	override val name = "Sub-tomogram averaging"
	override val hasFiles = true
	
	// TEMP: preview status during development
	// override val status = NodeConfig.NodeStatus.Preview

	val movieRefinement = NodeConfig.Data("movie-refinement", NodeConfig.Data.Type.MovieRefinement)
	val movieRefinementOut = NodeConfig.Data("movie-refinements-out", NodeConfig.Data.Type.MovieRefinement)
	
	override val inputs = listOf(movieRefinement)
	override val outputs = listOf(movieRefinementOut)
}
