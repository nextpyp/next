package edu.duke.bartesaghi.micromon.nodes


object TomographyAfterFlexibleRefinementNodeConfig : NodeConfig {

	const val ID = "tomo-flexible-refinement-after"

	override val id = ID
	override val configId = "tomo_flexible_refine_after"
	override val name = "3D refinement (after movies)"
	override val hasFiles = true

	val movieAfterRefinements = NodeConfig.Data("movie-after-refinement", NodeConfig.Data.Type.MovieFrameAfterRefinements)

	val movieFrameRefinements = NodeConfig.Data("movie-frame-refinements", NodeConfig.Data.Type.MovieFrameAfterRefinements)

	override val inputs = listOf(movieAfterRefinements)
	override val outputs = listOf(movieFrameRefinements)
}
