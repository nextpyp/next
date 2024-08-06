package edu.duke.bartesaghi.micromon.nodes


object SingleParticleDrgnNodeConfig : NodeConfig {

	const val ID = "sp-drgn"

	override val id = ID
	override val configId = "spr_heterogeneity"
	override val name = "Continuous Heterogeneity"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val inRefinements = NodeConfig.Data("inRefinements", NodeConfig.Data.Type.Refinements)
	val outRefinements = NodeConfig.Data("outRefinements", NodeConfig.Data.Type.Refinements)

	override val inputs = listOf(inRefinements)
	override val outputs = listOf(outRefinements)
}
