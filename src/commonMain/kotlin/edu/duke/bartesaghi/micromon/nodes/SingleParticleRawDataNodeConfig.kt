package edu.duke.bartesaghi.micromon.nodes

import edu.duke.bartesaghi.micromon.nodes.NodeConfig.NodeType


object SingleParticleRawDataNodeConfig : NodeConfig {

	const val ID = "sp-rawdata"

	override val id = ID
	override val configId = "spr_import_raw"
	override val name = "Single Particle (from Raw Data)"
	override val hasFiles = true
	override val type = NodeType.SingleParticleRawData

	val movies = NodeConfig.Data("movies", NodeConfig.Data.Type.Movies)

	override val inputs = emptyList<NodeConfig.Data>()
	override val outputs = listOf(movies)
}
