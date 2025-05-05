package edu.duke.bartesaghi.micromon.nodes


object TomographyInitialRefinementNodeConfig : NodeConfig {

	const val ID = "tomo-initial-refinement"

	override val id = ID
	override val configId = "tomo_initial_refine"
	override val name = "Ab-initio reconstruction"
	override val hasFiles = true

	val movieRefinement = NodeConfig.Data("movie-refinement", NodeConfig.Data.Type.MovieRefinement)

	val movieRefinements = NodeConfig.Data("movie-refinements", NodeConfig.Data.Type.MovieRefinements)

	override val inputs = listOf(movieRefinement)
	override val outputs = listOf(movieRefinements)
}
