package edu.duke.bartesaghi.micromon.nodes


object TomographyDenoisingEvalNodeConfig : NodeConfig {

	const val ID = "tomo-denoising-eval"

	override val id = ID
	override val configId = "tomo_denoise_eval"
	override val name = "Denoising (eval)"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val inModel = NodeConfig.Data("model", NodeConfig.Data.Type.DenoisingModel)
	val tomograms = NodeConfig.Data("outTomograms", NodeConfig.Data.Type.Tomograms)

	override val inputs = listOf(inModel)
	override val outputs = listOf(tomograms)
}
