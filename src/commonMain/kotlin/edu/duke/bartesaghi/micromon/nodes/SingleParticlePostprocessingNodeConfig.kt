package edu.duke.bartesaghi.micromon.nodes


object SingleParticlePostprocessingNodeConfig : NodeConfig {

	const val ID = "sp-postprocessing"

	override val id = ID
	override val configId = "spr_tomo_post_process"
	override val name = "Post-processing"
	override val hasFiles = true

	val refinements = NodeConfig.Data("refinements", NodeConfig.Data.Type.Refinements)
	val movieRefinements = NodeConfig.Data("movie-refinementsIn", NodeConfig.Data.Type.MovieRefinements)
	val movieRefinementsOut = NodeConfig.Data("movie-refinementsOut", NodeConfig.Data.Type.MovieFrameAfterRefinements)
	val frameRefinements = NodeConfig.Data("movie-refinements", NodeConfig.Data.Type.MovieFrameRefinements)

	override val inputs = listOf(refinements, movieRefinements, frameRefinements,movieRefinementsOut)
	override val outputs = emptyList<NodeConfig.Data>()
}
