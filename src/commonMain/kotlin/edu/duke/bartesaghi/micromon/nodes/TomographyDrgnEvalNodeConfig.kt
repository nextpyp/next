package edu.duke.bartesaghi.micromon.nodes


object TomographyDrgnEvalNodeConfig : NodeConfig {

	const val ID = "tomo-drgn-eval"

	override val id = ID
	override val configId = "tomo_drgn_analyze"
	override val name = "tomoDRGN (analyze)"
	override val hasFiles = true
	
	// TEMP: preview status during development
	// override val status = NodeConfig.NodeStatus.Preview

	val inModel = NodeConfig.Data("model", NodeConfig.Data.Type.DrgnModel)
	val movieRefinements = NodeConfig.Data("movie-refinements", NodeConfig.Data.Type.DrgnParticles)

	override val inputs = listOf(inModel)
	override val outputs = listOf(movieRefinements)
}
