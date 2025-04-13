package edu.duke.bartesaghi.micromon.nodes


object TomographyPickingNodeConfig : NodeConfig {

	const val ID = "tomo-picking"

	override val id = ID
	override val configId = "tomo_picking"
	override val name = "Particle picking"
	override val hasFiles = true

	val tomograms = NodeConfig.Data("tomograms", NodeConfig.Data.Type.Tomograms)
	val movieRefinement = NodeConfig.Data("movieRefinement", NodeConfig.Data.Type.MovieRefinement)

	override val inputs = listOf(tomograms)
	override val outputs = listOf(movieRefinement)
}
