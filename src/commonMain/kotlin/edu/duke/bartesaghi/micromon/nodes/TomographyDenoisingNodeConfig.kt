package edu.duke.bartesaghi.micromon.nodes


object TomographyDenoisingNodeConfig : NodeConfig {

	const val ID = "tomo-denoising"

	override val id = ID
	override val configId = "tomo_denoise"
	override val name = "Denoising"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val model = NodeConfig.Data("model", NodeConfig.Data.Type.DenoisingModel)
	val tomograms = NodeConfig.Data("outTomograms", NodeConfig.Data.Type.Tomograms)

	override val inputs = listOf(model)
	override val outputs = listOf(tomograms)
}
