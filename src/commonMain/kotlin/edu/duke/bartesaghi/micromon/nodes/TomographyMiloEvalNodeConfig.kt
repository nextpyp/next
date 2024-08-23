package edu.duke.bartesaghi.micromon.nodes


object TomographyMiloEvalNodeConfig : NodeConfig {

	const val ID = "tomo-milo"

	override val id = ID
	override val configId = "tomo_milo_eval"
	override val name = "MiLoPYP Evaluation"
	override val hasFiles = true
	// TEMP: preview status during development
	override val status = NodeConfig.NodeStatus.Preview

	val model = NodeConfig.Data("model", NodeConfig.Data.Type.MiloModel)
	val tomograms = NodeConfig.Data("tomograms", NodeConfig.Data.Type.Tomograms)
	val particles = NodeConfig.Data("particles", NodeConfig.Data.Type.ParticlesParquet)

	override val inputs = listOf(tomograms, model)
	override val outputs = listOf(particles)
}
