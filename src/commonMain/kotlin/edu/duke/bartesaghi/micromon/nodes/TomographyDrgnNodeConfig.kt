package edu.duke.bartesaghi.micromon.nodes


object TomographyDrgnNodeConfig : NodeConfig {

	const val ID = "tomo-drgn"

	override val id = ID
	override val configId = "tomo_drgn"
	override val name = "Continuous Heterogeneity"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val inMovieRefinements = NodeConfig.Data("inMovieRefinements", NodeConfig.Data.Type.MovieRefinements)

	override val inputs = listOf(inMovieRefinements)
	override val outputs = emptyList<NodeConfig.Data>()
}
