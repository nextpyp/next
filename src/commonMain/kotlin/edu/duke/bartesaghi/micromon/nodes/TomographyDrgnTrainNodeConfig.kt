package edu.duke.bartesaghi.micromon.nodes


object TomographyDrgnTrainNodeConfig : NodeConfig {

	const val ID = "tomo-drgn-train"

	override val id = ID
	override val configId = "tomo_drgn_train"
	override val name = "Continuous Heterogeneity (train)"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val inMovieRefinements = NodeConfig.Data("inMovieRefinements", NodeConfig.Data.Type.MovieRefinements)
	val model = NodeConfig.Data("model", NodeConfig.Data.Type.DrgnModel)

	override val inputs = listOf(inMovieRefinements)
	override val outputs = listOf(model)
}
