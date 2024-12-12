package edu.duke.bartesaghi.micromon.nodes


object TomographyPreprocessingNodeConfig : NodeConfig {

	const val ID = "tomo-preprocessing"

	override val id = ID
	override val configId = "tomo_pre_process"
	override val name = "Pre-processing (legacy)"
	override val hasFiles = true
	override val status = NodeConfig.NodeStatus.Legacy

	val tiltSeries = NodeConfig.Data("tilt-series", NodeConfig.Data.Type.TiltSeries)
	val movieRefinement = NodeConfig.Data("movie-refinement", NodeConfig.Data.Type.MovieRefinement)

	override val inputs = listOf(tiltSeries)
	override val outputs = listOf(movieRefinement)
}
