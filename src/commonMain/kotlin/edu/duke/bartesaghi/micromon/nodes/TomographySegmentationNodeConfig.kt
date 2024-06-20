package edu.duke.bartesaghi.micromon.nodes


object TomographySegmentationNodeConfig : NodeConfig {

	const val ID = "tomo-segmentation"

	override val id = ID
	override val configId = "tomo_segment"
	override val name = "Segmentation"
	override val hasFiles = true

	val tiltSeries = NodeConfig.Data("tilt-series", NodeConfig.Data.Type.TiltSeries)
	val segmentation = NodeConfig.Data("segmentation", NodeConfig.Data.Type.Segmentation)

	override val inputs = listOf(tiltSeries)
	override val outputs = listOf(segmentation)
}
