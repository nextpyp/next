package edu.duke.bartesaghi.micromon.nodes


object TomographyDenoisingNodeConfig : NodeConfig {

	const val ID = "tomo-denoising"

	override val id = ID
	override val configId = "tomo_denoise"
	override val name = "Denoising"
	override val hasFiles = true

	val inTomograms = NodeConfig.Data("inTomograms", NodeConfig.Data.Type.Tomograms)
	val outTomograms = NodeConfig.Data("outTomograms", NodeConfig.Data.Type.Tomograms)

	override val inputs = listOf(inTomograms)
	override val outputs = listOf(outTomograms)
}
