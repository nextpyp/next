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
	val segmentation = NodeConfig.Data("segmentation", NodeConfig.Data.Type.Segmentation)
	val particles = NodeConfig.Data("particles", NodeConfig.Data.Type.Particles)
	val particleCoords = NodeConfig.Data("particleCoords", NodeConfig.Data.Type.ParticleCoords)

	override val inputs = listOf(tomograms, segmentation)
	override val outputs = listOf(particles, particleCoords)
}
