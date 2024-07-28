package edu.duke.bartesaghi.micromon.nodes


object SingleParticleDenoisingNodeConfig : NodeConfig {

	const val ID = "sp-denoising"

	override val id = ID
	override val configId = "spr_denoise"
	override val name = "Denoising"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val inMicrographs = NodeConfig.Data("inMicrographs", NodeConfig.Data.Type.Micrographs)
	val outMicrographs = NodeConfig.Data("outMicrographs", NodeConfig.Data.Type.Micrographs)

	override val inputs = listOf(inMicrographs)
	override val outputs = listOf(outMicrographs)
}
