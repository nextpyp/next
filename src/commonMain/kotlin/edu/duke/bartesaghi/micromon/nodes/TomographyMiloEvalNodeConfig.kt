package edu.duke.bartesaghi.micromon.nodes


object TomographyMiloEvalNodeConfig : NodeConfig {

	const val ID = "tomo-milo"

	override val id = ID
	override val configId = "tomo_milo_eval"
	override val name = "MiLoPYP (eval)"
	override val hasFiles = true

	val model = NodeConfig.Data("model", NodeConfig.Data.Type.MiloModel)
	val tomograms = NodeConfig.Data("tomograms", NodeConfig.Data.Type.Tomograms)
	val coordinates = NodeConfig.Data("coordinates", NodeConfig.Data.Type.MiloCoordinates)

	override val inputs = listOf(tomograms, model)
	override val outputs = listOf(coordinates)
}
