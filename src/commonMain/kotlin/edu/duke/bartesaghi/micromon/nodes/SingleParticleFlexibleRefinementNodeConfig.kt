package edu.duke.bartesaghi.micromon.nodes


object SingleParticleFlexibleRefinementNodeConfig : NodeConfig {

	const val ID = "sp-flexible-refinement"

	override val id = ID
	override val configId = "spr_flexible_refine"
	override val name = "Movie refinement"
	override val hasFiles = true

	val refinements = NodeConfig.Data("refinements", NodeConfig.Data.Type.Refinements)
	val movieRefinementsIn = NodeConfig.Data("movie-refinements-in", NodeConfig.Data.Type.MovieFrameRefinement)
	val movieRefinementsOut = NodeConfig.Data("movie-refinements-out", NodeConfig.Data.Type.MovieFrameRefinements)


	override val inputs = listOf(refinements, movieRefinementsIn)
	override val outputs = listOf(movieRefinementsOut)
}
