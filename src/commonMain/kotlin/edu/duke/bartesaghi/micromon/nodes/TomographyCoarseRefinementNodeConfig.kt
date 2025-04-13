package edu.duke.bartesaghi.micromon.nodes


object TomographyCoarseRefinementNodeConfig : NodeConfig {

	const val ID = "tomo-coarse-refinement"

	override val id = ID
	override val configId = "tomo_coarse_refine"
	override val name = "Particle refinement (legacy)"
	override val hasFiles = true
	override val status = NodeConfig.NodeStatus.Legacy
	
	val movieRefinement = NodeConfig.Data("movie-refinement", NodeConfig.Data.Type.MovieRefinement)
	val movieRefinementsIn = NodeConfig.Data("movie-refinements-in", NodeConfig.Data.Type.MovieRefinements)
	val movieRefinements = NodeConfig.Data("movie-refinements", NodeConfig.Data.Type.MovieRefinements)

	override val inputs = listOf(movieRefinement, movieRefinementsIn)
	override val outputs = listOf(movieRefinements)
}
