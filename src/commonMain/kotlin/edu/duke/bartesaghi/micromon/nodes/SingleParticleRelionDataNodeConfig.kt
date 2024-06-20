package edu.duke.bartesaghi.micromon.nodes

import edu.duke.bartesaghi.micromon.nodes.NodeConfig.NodeType


object SingleParticleRelionDataNodeConfig : NodeConfig {

	const val ID = "sp-reliondata"

	override val id = ID
	override val configId = "spr_import_star"
	override val name = "Single Particle (from Star)"
	override val hasFiles = true
	override val type = NodeType.SingleParticleRawData

	val refinement = NodeConfig.Data("refinement", NodeConfig.Data.Type.Refinement)

	override val inputs = emptyList<NodeConfig.Data>()
	override val outputs = listOf(refinement)
}
