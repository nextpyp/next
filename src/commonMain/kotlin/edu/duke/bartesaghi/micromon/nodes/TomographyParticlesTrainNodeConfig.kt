package edu.duke.bartesaghi.micromon.nodes


object TomographyParticlesTrainNodeConfig : NodeConfig {

	const val ID = "tomo-particles-train"

	override val id = ID
	override val configId = "tomo_particles_train"
	override val name = "Particle-Picking (train)"
	override val hasFiles = true

	val particles = NodeConfig.Data("particles", NodeConfig.Data.Type.MovieRefinement)
	val coordinates = NodeConfig.Data("coordinates", NodeConfig.Data.Type.MiloCoordinates)
	val model = NodeConfig.Data("model", NodeConfig.Data.Type.ParticlesModel)

	override val inputs = listOf(particles, coordinates)
	override val outputs = listOf(model)
}
