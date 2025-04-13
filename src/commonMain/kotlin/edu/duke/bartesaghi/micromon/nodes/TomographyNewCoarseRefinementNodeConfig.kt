package edu.duke.bartesaghi.micromon.nodes


object TomographyNewCoarseRefinementNodeConfig : NodeConfig {

	const val ID = "tomo-new-coarse-refinement"

	override val id = ID
	override val configId = "tomo_new_coarse_refine"
	override val name = "3D refinement"
	override val hasFiles = true

	val movieRefinementsIn = NodeConfig.Data("movie-refinements-in", NodeConfig.Data.Type.MovieRefinements)
	val movieRefinements = NodeConfig.Data("movie-refinements", NodeConfig.Data.Type.MovieRefinements)

	override val inputs = listOf(movieRefinementsIn)
	override val outputs = listOf(movieRefinements)
}
