package edu.duke.bartesaghi.micromon.nodes


object TomographyDenoisingTrainingNodeConfig : NodeConfig {

	const val ID = "tomo-denoising-train"

	override val id = ID
	override val configId = "tomo_denoise_train"
	override val name = "Denoising (train)"
	override val hasFiles = true

	val tomograms = NodeConfig.Data("inTomograms", NodeConfig.Data.Type.Tomograms)
	val model = NodeConfig.Data("model", NodeConfig.Data.Type.DenoisingModel)

	override val inputs = listOf(tomograms)
	override val outputs = listOf(model)
}
