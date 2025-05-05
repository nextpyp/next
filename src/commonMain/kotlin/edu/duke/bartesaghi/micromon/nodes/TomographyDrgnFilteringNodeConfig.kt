package edu.duke.bartesaghi.micromon.nodes


object TomographyDrgnFilteringNodeConfig : NodeConfig {

	const val ID = "tomo-drgn-filter"

	override val id = ID
	override val configId = "tomo_drgn_filter"
	override val name = "tomoDRGN (filter-star)"
	override val hasFiles = true
	
	val movieRefinementsIn = NodeConfig.Data("movie-refinementsIn", NodeConfig.Data.Type.DrgnParticles)
	val movieRefinementsOut = NodeConfig.Data("movie-refinementsOut", NodeConfig.Data.Type.MovieRefinements)

	override val inputs = listOf(movieRefinementsIn)
	override val outputs = listOf(movieRefinementsOut)
}
