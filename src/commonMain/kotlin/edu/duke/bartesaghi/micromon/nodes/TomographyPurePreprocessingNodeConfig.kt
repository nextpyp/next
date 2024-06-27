package edu.duke.bartesaghi.micromon.nodes


object TomographyPurePreprocessingNodeConfig : NodeConfig {

	const val ID = "tomo-pure-preprocessing"

	override val id = ID
	override val configId = "tomo_pure_preprocess"
	override val name = "Pre-processing (pure)"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val tiltSeries = NodeConfig.Data("tilt-series", NodeConfig.Data.Type.TiltSeries)
	val tomograms = NodeConfig.Data("tomograms", NodeConfig.Data.Type.Tomograms)

	override val inputs = listOf(tiltSeries)
	override val outputs = listOf(tomograms)
}
