package edu.duke.bartesaghi.micromon.nodes


object TomographyRelionRefinementNodeConfig : NodeConfig {

	const val ID = "tomo-relion-refinement"

	override val id = ID
	override val configId = "tomo_relion_refine"
	override val name = "Relion refinement"
	override val hasFiles = true
	override val status = NodeConfig.NodeStatus.Preview

	val movieRefinement = NodeConfig.Data("movie-refinement", NodeConfig.Data.Type.MovieRefinement)
	val movieRefinements = NodeConfig.Data("movie-refinements", NodeConfig.Data.Type.MovieRefinements)

	override val inputs = listOf(movieRefinement)
	override val outputs = listOf(movieRefinements)

}
