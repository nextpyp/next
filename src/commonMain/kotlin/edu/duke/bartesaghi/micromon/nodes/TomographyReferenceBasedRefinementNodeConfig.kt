package edu.duke.bartesaghi.micromon.nodes


object TomographyReferenceBasedRefinementNodeConfig : NodeConfig {

	const val ID = "tomo-reference-refinement"

	override val id = ID
	override val configId = "tomo_reference_refine"
	override val name = "Reference-based refinement"
	override val hasFiles = true

	val movieRefinement = NodeConfig.Data("movie-refinement", NodeConfig.Data.Type.MovieRefinement)

	val movieRefinements = NodeConfig.Data("movie-refinements", NodeConfig.Data.Type.MovieRefinements)

	override val inputs = listOf(movieRefinement)
	override val outputs = listOf(movieRefinements)
}
