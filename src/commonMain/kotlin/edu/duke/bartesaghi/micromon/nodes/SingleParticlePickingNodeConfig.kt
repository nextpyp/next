package edu.duke.bartesaghi.micromon.nodes


object SingleParticlePickingNodeConfig : NodeConfig {

	const val ID = "sp-picking"

	override val id = ID
	override val configId = "spr_picking"
	override val name = "Particle Picking"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val micrographs = NodeConfig.Data("micrographs", NodeConfig.Data.Type.Micrographs)
	val refinement = NodeConfig.Data("refinement", NodeConfig.Data.Type.Refinement)

	override val inputs = listOf(micrographs)
	override val outputs = listOf(refinement)
}
