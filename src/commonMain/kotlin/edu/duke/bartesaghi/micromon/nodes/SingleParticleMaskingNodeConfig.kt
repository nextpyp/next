package edu.duke.bartesaghi.micromon.nodes


object SingleParticleMaskingNodeConfig : NodeConfig {

	const val ID = "sp-masking"

	override val id = ID
	override val configId = "spr_tomo_map_mask"
	override val name = "Masking"
	override val hasFiles = true

	val refinements = NodeConfig.Data("refinements", NodeConfig.Data.Type.Refinements)
	val movieRefinements = NodeConfig.Data("movie-refinementsIn", NodeConfig.Data.Type.MovieRefinements)
	val movieRefinementsClasses = NodeConfig.Data("movie-refinementsClassesIn", NodeConfig.Data.Type.MovieRefinementsClasses)
	val movieRefinementsOut = NodeConfig.Data("movie-refinementsOut", NodeConfig.Data.Type.MovieFrameAfterRefinements)
	val frameRefinements = NodeConfig.Data("movie-refinements", NodeConfig.Data.Type.MovieFrameRefinements)

	override val inputs = listOf(refinements, movieRefinements, movieRefinementsClasses, frameRefinements, movieRefinementsOut)
	override val outputs = emptyList<NodeConfig.Data>()
}
