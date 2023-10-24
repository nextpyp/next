package edu.duke.bartesaghi.micromon.nodes


object SingleParticlePreprocessingNodeConfig : NodeConfig {

	const val ID = "sp-preprocessing"

	override val id = ID
	override val configId = "spr_pre_process"
	override val name = "Pre-processing"
	override val hasFiles = true

	val movies = NodeConfig.Data("movies", NodeConfig.Data.Type.Movies)
	val refinement = NodeConfig.Data("refinement", NodeConfig.Data.Type.Refinement)

	override val inputs = listOf(movies)
	override val outputs = listOf(refinement)
}
