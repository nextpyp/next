package edu.duke.bartesaghi.micromon.nodes


object TomographyPickingModelNodeConfig : NodeConfig {

	const val ID = "tomo-picking-model"

	override val id = ID
	override val configId = "tomo_picking_train"
	override val name = "Particle Model Training"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val particles = NodeConfig.Data("particles", NodeConfig.Data.Type.MovieRefinement)
	val model = NodeConfig.Data("model", NodeConfig.Data.Type.ParticlesModel)

	override val inputs = listOf(particles)
	override val outputs = listOf(model)
}
