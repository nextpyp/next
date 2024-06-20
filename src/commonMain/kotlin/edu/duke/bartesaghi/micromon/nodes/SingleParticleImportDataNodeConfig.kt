package edu.duke.bartesaghi.micromon.nodes

import edu.duke.bartesaghi.micromon.nodes.NodeConfig.NodeType


object SingleParticleImportDataNodeConfig : NodeConfig {

	const val ID = "sp-import"

	override val id = ID
	override val configId = "spr_import"
	override val name = "Single Particle (from PYP)"
	override val hasFiles = true
	override val type = NodeType.SingleParticleRawData

	val refinement = NodeConfig.Data("refinement", NodeConfig.Data.Type.Refinement)

	override val inputs = emptyList<NodeConfig.Data>()
	override val outputs = listOf(refinement)
}
