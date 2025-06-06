package edu.duke.bartesaghi.micromon.nodes


object TomographyMiloTrainNodeConfig : NodeConfig {

	const val ID = "tomo-milo-train"

	override val id = ID
	override val configId = "tomo_milo_train"
	override val name = "MiLoPYP (train)"
	override val hasFiles = true

	val tomograms = NodeConfig.Data("tomograms", NodeConfig.Data.Type.Tomograms)
	val model = NodeConfig.Data("model", NodeConfig.Data.Type.MiloModel)

	override val inputs = listOf(tomograms)
	override val outputs = listOf(model)
}
