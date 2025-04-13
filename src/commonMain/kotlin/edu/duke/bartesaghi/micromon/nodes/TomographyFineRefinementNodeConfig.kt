package edu.duke.bartesaghi.micromon.nodes


object TomographyFineRefinementNodeConfig : NodeConfig {

	const val ID = "tomo-fine-refinement"

	override val id = ID
	override val configId = "tomo_map_clean"
	override val name = "Particle filtering"
	override val hasFiles = true

	val movieRefinementsIn = NodeConfig.Data("movie-refinementsIn", NodeConfig.Data.Type.MovieRefinements)
	val movieRefinementsClassesIn = NodeConfig.Data("movie-refinementsClassesIn", NodeConfig.Data.Type.MovieRefinementsClasses)
	val movieRefinementsOut = NodeConfig.Data("movie-refinementsOut", NodeConfig.Data.Type.MovieRefinements)

	override val inputs = listOf(movieRefinementsIn, movieRefinementsClassesIn)
	override val outputs = listOf(movieRefinementsOut)
}
