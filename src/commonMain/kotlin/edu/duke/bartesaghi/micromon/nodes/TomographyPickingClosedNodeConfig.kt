package edu.duke.bartesaghi.micromon.nodes


object TomographyPickingClosedNodeConfig : NodeConfig {

	const val ID = "tomo-picking-closed"

	override val id = ID
	override val configId = "tomo_par_close"
	override val name = "Particle-Picking (closed surfaces)"
	override val hasFiles = true

	val segmentation = NodeConfig.Data("segmentation", NodeConfig.Data.Type.SegmentationClosed)
	val particles = NodeConfig.Data("particles", NodeConfig.Data.Type.MovieRefinement)

	override val inputs = listOf(segmentation)
	override val outputs = listOf(particles)
}
