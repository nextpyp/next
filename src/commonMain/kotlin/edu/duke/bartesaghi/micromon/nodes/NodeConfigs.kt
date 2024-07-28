package edu.duke.bartesaghi.micromon.nodes


object NodeConfigs {

	val nodes = listOf(
		SingleParticleRawDataNodeConfig,
		SingleParticleRelionDataNodeConfig,
		SingleParticleImportDataNodeConfig,
		SingleParticleSessionDataNodeConfig,
		SingleParticlePreprocessingNodeConfig,
		SingleParticlePurePreprocessingNodeConfig,
		SingleParticleDenoisingNodeConfig,
		SingleParticlePickingNodeConfig,
		SingleParticleDrgnNodeConfig,
		SingleParticleCoarseRefinementNodeConfig,
		SingleParticleFineRefinementNodeConfig,
		SingleParticleFlexibleRefinementNodeConfig,
		SingleParticlePostprocessingNodeConfig,
		SingleParticleMaskingNodeConfig,
		TomographyRawDataNodeConfig,
		TomographyRelionDataNodeConfig,
		TomographyImportDataNodeConfig,
		TomographySessionDataNodeConfig,
		TomographyPreprocessingNodeConfig,
		TomographyPurePreprocessingNodeConfig,
		TomographyDenoisingNodeConfig,
		TomographyPickingNodeConfig,
		TomographyPickingOpenNodeConfig,
		TomographyPickingClosedNodeConfig,
		TomographyDrgnNodeConfig,
		TomographyCoarseRefinementNodeConfig,
		TomographyFineRefinementNodeConfig,
		TomographyMovieCleaningNodeConfig,
		TomographyFlexibleRefinementNodeConfig
	).apply {

		// make sure all the node IDs are unique
		val ids = HashSet<String>()
		val duplicatedIds = this.map { it.id }
			.filter { !ids.add(it) }
			.toSet()
		if (duplicatedIds.isNotEmpty()) {
			throw Error("Node IDs should be unique! Duplicated ids: $duplicatedIds")
		}
	}

	private val nodesLookup = nodes.associateBy { it.id }

	operator fun get(id: String): NodeConfig? =
		nodesLookup[id]

	fun findNodesUsing(data: NodeConfig.Data): List<Pair<NodeConfig,List<NodeConfig.Data>>> =
		nodes.mapNotNull { nodeConfig ->
			val matchingInputs = nodeConfig.findInputs(data)
			if (matchingInputs.isNotEmpty()) {
				nodeConfig to matchingInputs
			} else {
				null
			}
		}
}
