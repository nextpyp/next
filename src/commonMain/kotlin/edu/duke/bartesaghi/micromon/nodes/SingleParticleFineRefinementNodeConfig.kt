package edu.duke.bartesaghi.micromon.nodes


object SingleParticleFineRefinementNodeConfig : NodeConfig {

	const val ID = "sp-fine-refinement"

	override val id = ID
	override val configId = "spr_map_clean"
	override val name = "Particle filtering"
	override val hasFiles = true

	val refinementsIn = NodeConfig.Data("refinements-in", NodeConfig.Data.Type.Refinements)
	val refinementsOut = NodeConfig.Data("refinements-out", NodeConfig.Data.Type.Refinements)

	override val inputs = listOf(refinementsIn)
	override val outputs = listOf(refinementsOut)
}
