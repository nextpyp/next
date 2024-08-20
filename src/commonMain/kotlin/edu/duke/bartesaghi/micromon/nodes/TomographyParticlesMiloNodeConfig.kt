package edu.duke.bartesaghi.micromon.nodes


object TomographyParticlesMiloNodeConfig : NodeConfig {

	const val ID = "tomo-milo"

	override val id = ID
	override val configId = "tomo_milo"
	override val name = "MiLoPYP"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val tomograms = NodeConfig.Data("tomograms", NodeConfig.Data.Type.Tomograms)
	val particles = NodeConfig.Data("particles", NodeConfig.Data.Type.ParticlesParquet)

	override val inputs = listOf(tomograms)
	override val outputs = listOf(particles)
}
