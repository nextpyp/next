package edu.duke.bartesaghi.micromon.nodes


object TomographyDrgnEvalVolsNodeConfig : NodeConfig {

	const val ID = "tomo-drgn-eval-vols"

	override val id = ID
	override val configId = "tomo_drgn_analyze_vols"
	override val name = "tomoDRGN (analyze volumes)"
	override val hasFiles = true
	override val status = NodeConfig.NodeStatus.Preview

	// TEMP: preview status during development
	// override val status = NodeConfig.NodeStatus.Preview

	val inModel = NodeConfig.Data("model", NodeConfig.Data.Type.DrgnModel)
	val movieRefinements = NodeConfig.Data("movie-refinements", NodeConfig.Data.Type.DrgnParticles)

	override val inputs = listOf(inModel)
	override val outputs = listOf(movieRefinements)
}
