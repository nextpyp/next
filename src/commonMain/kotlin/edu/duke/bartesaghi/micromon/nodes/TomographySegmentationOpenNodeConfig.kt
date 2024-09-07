package edu.duke.bartesaghi.micromon.nodes


object TomographySegmentationOpenNodeConfig : NodeConfig {

	const val ID = "tomo-segmentation-open"

	override val id = ID
	override val configId = "tomo_segment_open"
	override val name = "Segmentation (open surfaces)"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val tomograms = NodeConfig.Data("tomograms", NodeConfig.Data.Type.Tomograms)
	// val segmentation = NodeConfig.Data("segmentation", NodeConfig.Data.Type.SegmentationOpen)

	override val inputs = listOf(tomograms)
	// override val outputs = listOf(segmentation)
	override val outputs = emptyList<NodeConfig.Data>()
}
