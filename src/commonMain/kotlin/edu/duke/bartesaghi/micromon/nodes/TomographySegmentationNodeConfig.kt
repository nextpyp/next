package edu.duke.bartesaghi.micromon.nodes


object TomographySegmentationNodeConfig : NodeConfig {

	const val ID = "tomo-segmentation"

	override val id = ID
	override val configId = "tomo_segment_close"
	override val name = "Segmentation"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val tomograms = NodeConfig.Data("tomograms", NodeConfig.Data.Type.Tomograms)
	val segmentation = NodeConfig.Data("segmentation", NodeConfig.Data.Type.Segmentation)

	override val inputs = listOf(tomograms)
	override val outputs = listOf(segmentation)
}
