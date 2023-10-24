package edu.duke.bartesaghi.micromon.nodes


object SingleParticleSessionDataNodeConfig : NodeConfig {

	const val ID = "sp-session"

	override val id = ID
	override val configId = "spr_pre_process"
	override val name = "Single Particle (from Session)"
	override val hasFiles = true

	val refinement = NodeConfig.Data("refinement", NodeConfig.Data.Type.Refinement)

	override val inputs = emptyList<NodeConfig.Data>()
	override val outputs = listOf(refinement)
}
