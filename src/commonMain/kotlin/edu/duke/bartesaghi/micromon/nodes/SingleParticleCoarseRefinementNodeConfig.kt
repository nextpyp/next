package edu.duke.bartesaghi.micromon.nodes


object SingleParticleCoarseRefinementNodeConfig : NodeConfig {

	const val ID = "sp-coarse-refinement"

	override val id = ID
	override val configId = "spr_coarse_refine"
	override val name = "Particle refinement"
	override val hasFiles = true

	val refinement = NodeConfig.Data("refinement", NodeConfig.Data.Type.Refinement)
	val refinementsIn = NodeConfig.Data("refinements-in", NodeConfig.Data.Type.Refinements)
	val refinements = NodeConfig.Data("refinements", NodeConfig.Data.Type.Refinements)

	override val inputs = listOf(refinement, refinementsIn)
	override val outputs = listOf(refinements)
}
