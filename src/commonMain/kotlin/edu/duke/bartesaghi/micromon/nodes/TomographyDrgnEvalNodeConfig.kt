package edu.duke.bartesaghi.micromon.nodes


object TomographyDrgnEvalNodeConfig : NodeConfig {

	const val ID = "tomo-drgn-eval"

	override val id = ID
	override val configId = "tomo_drgn_eval"
	override val name = "Continuous Heterogeneity (eval)"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val model = NodeConfig.Data("model", NodeConfig.Data.Type.DrgnModel)
	val inMovieRefinements = NodeConfig.Data("inMovieRefinements", NodeConfig.Data.Type.MovieRefinements)

	override val inputs = listOf(model,inMovieRefinements)
	override val outputs = emptyList<NodeConfig.Data>()
}
