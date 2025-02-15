package edu.duke.bartesaghi.micromon.nodes


object SingleParticlePurePreprocessingNodeConfig : NodeConfig {

	const val ID = "sp-pure-preprocessing"

	override val id = ID
	override val configId = "spr_pure_preprocess"
	override val name = "Pre-processing (preview)"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val movies = NodeConfig.Data("movies", NodeConfig.Data.Type.Movies)
	val micrographs = NodeConfig.Data("micrographs", NodeConfig.Data.Type.Micrographs)

	override val inputs = listOf(movies)
	override val outputs = listOf(micrographs)
}
