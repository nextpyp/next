package edu.duke.bartesaghi.micromon.nodes


object TomographyNewCoarseClassificationNodeConfig : NodeConfig {

	const val ID = "tomo-new-coarse-classification"

	override val id = ID
	override val configId = "tomo_new_coarse_classification"
	override val name = "3D classification"
	override val hasFiles = true

	val movieRefinementsIn = NodeConfig.Data("movie-refinements-in", NodeConfig.Data.Type.MovieRefinements)
	val movieRefinements = NodeConfig.Data("movie-refinements", NodeConfig.Data.Type.MovieRefinementsClasses)

	override val inputs = listOf(movieRefinementsIn)
	override val outputs = listOf(movieRefinements)
}
