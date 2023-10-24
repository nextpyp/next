package edu.duke.bartesaghi.micromon.nodes


object SingleParticleImportDataNodeConfig : NodeConfig {

	const val ID = "sp-import"

	override val id = ID
	override val configId = "spr_import"
	override val name = "Single Particle (from PYP)"
	override val hasFiles = true

	val refinement = NodeConfig.Data("refinement", NodeConfig.Data.Type.Refinement)

	override val inputs = emptyList<NodeConfig.Data>()
	override val outputs = listOf(refinement)
}
