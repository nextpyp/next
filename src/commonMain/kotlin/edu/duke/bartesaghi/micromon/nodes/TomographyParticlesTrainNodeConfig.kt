package edu.duke.bartesaghi.micromon.nodes


object TomographyParticlesTrainNodeConfig : NodeConfig {

	const val ID = "tomo-particles-train"

	override val id = ID
	override val configId = "tomo_particles_train"
	override val name = "Particle Model Training"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val particles = NodeConfig.Data("particles", NodeConfig.Data.Type.MovieRefinement)
	val particlesParquet = NodeConfig.Data("particlesParquet", NodeConfig.Data.Type.ParticlesParquet)
	val model = NodeConfig.Data("model", NodeConfig.Data.Type.ParticlesModel)

	override val inputs = listOf(particles, particlesParquet)
	override val outputs = listOf(model)
}
