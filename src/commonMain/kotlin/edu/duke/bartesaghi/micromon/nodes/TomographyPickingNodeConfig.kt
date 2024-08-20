package edu.duke.bartesaghi.micromon.nodes


object TomographyPickingNodeConfig : NodeConfig {

	const val ID = "tomo-picking"

	override val id = ID
	override val configId = "tomo_picking"
	override val name = "Particle-Picking"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val tomograms = NodeConfig.Data("tomograms", NodeConfig.Data.Type.Tomograms)
	val particlesModel = NodeConfig.Data("particlesModel", NodeConfig.Data.Type.ParticlesModel)
	val movieRefinement = NodeConfig.Data("movieRefinement", NodeConfig.Data.Type.MovieRefinement)

	override val inputs = listOf(tomograms, particlesModel)
	override val outputs = listOf(movieRefinement)
}
