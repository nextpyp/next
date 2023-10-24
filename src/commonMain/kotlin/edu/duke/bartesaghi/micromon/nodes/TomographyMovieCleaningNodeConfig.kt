package edu.duke.bartesaghi.micromon.nodes


object TomographyMovieCleaningNodeConfig : NodeConfig {

	const val ID = "tomo-movie-clean"

	override val id = ID
	override val configId = "tomo_map_clean"
	override val name = "Particle cleaning"
	override val hasFiles = true

	val movieRefinementsIn = NodeConfig.Data("movie-refinementsIn", NodeConfig.Data.Type.MovieFrameAfterRefinements)
	val movieRefinementsOut = NodeConfig.Data("movie-refinementsOut", NodeConfig.Data.Type.MovieFrameAfterRefinements)

	override val inputs = listOf(movieRefinementsIn)
	override val outputs = listOf(movieRefinementsOut)
}
