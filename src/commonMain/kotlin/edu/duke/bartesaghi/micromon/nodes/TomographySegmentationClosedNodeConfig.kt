package edu.duke.bartesaghi.micromon.nodes


object TomographySegmentationClosedNodeConfig : NodeConfig {

	const val ID = "tomo-segmentation-closed"

	override val id = ID
	override val configId = "tomo_segment_close"
	override val name = "Segmentation (closed surfaces)"
	override val hasFiles = true

	val particles = NodeConfig.Data("particles", NodeConfig.Data.Type.MovieRefinement)
	val segmentation = NodeConfig.Data("segmentation", NodeConfig.Data.Type.SegmentationClosed)

	override val inputs = listOf(particles)
	override val outputs = listOf(segmentation)
}
