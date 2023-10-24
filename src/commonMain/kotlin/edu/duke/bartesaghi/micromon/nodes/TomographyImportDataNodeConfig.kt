package edu.duke.bartesaghi.micromon.nodes


object TomographyImportDataNodeConfig : NodeConfig {

	const val ID = "tomo-import"

	override val id = ID
	override val configId = "tomo_import"
	override val name = "Tomography (from PYP)"
	override val hasFiles = true

	val movieRefinement = NodeConfig.Data("movie-refinement", NodeConfig.Data.Type.MovieRefinement)

	override val inputs = emptyList<NodeConfig.Data>()
	override val outputs = listOf(movieRefinement)
}
