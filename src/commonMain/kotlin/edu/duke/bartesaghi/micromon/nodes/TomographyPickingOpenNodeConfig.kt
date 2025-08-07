package edu.duke.bartesaghi.micromon.nodes


object TomographyPickingOpenNodeConfig : NodeConfig {

	const val ID = "tomo-picking-open"

	override val id = ID
	override val configId = "tomo_par_open"
	override val name = "Particle picking (open surfaces, filaments)"
	override val hasFiles = true

	val segmentation = NodeConfig.Data("segmentation", NodeConfig.Data.Type.SegmentationOpen)
	val particles = NodeConfig.Data("particles", NodeConfig.Data.Type.MovieRefinement)

	override val inputs = listOf(segmentation)
	override val outputs = listOf(particles)
}
