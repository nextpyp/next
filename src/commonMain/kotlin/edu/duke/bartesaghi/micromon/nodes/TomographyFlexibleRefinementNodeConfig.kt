package edu.duke.bartesaghi.micromon.nodes


object TomographyFlexibleRefinementNodeConfig : NodeConfig {

	const val ID = "tomo-flexible-refinement"

	override val id = ID
	override val configId = "tomo_flexible_refine"
	override val name = "Movie refinement"
	override val hasFiles = true

	val movieRefinements = NodeConfig.Data("movie-refinements", NodeConfig.Data.Type.MovieRefinements)
	val movieAfterRefinements = NodeConfig.Data("movie-after-refinement", NodeConfig.Data.Type.MovieFrameAfterRefinements)

	val movieFrameRefinements = NodeConfig.Data("movie-frame-refinements", NodeConfig.Data.Type.MovieFrameAfterRefinements)

	override val inputs = listOf(movieRefinements,movieAfterRefinements)
	override val outputs = listOf(movieFrameRefinements)
}
