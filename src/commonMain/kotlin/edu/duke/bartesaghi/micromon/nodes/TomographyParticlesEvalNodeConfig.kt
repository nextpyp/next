package edu.duke.bartesaghi.micromon.nodes


object TomographyParticlesEvalNodeConfig : NodeConfig {

	const val ID = "tomo-particles-eval"

	override val id = ID
	override val configId = "tomo_particles_eval"
	override val name = "Particle picking (eval)"
	override val hasFiles = true

	val model = NodeConfig.Data("model", NodeConfig.Data.Type.ParticlesModel)
	val tomograms = NodeConfig.Data("tomograms", NodeConfig.Data.Type.Tomograms)
	val particles = NodeConfig.Data("particles", NodeConfig.Data.Type.MovieRefinement)

	override val inputs = listOf(model,tomograms)
	override val outputs = listOf(particles)
}
