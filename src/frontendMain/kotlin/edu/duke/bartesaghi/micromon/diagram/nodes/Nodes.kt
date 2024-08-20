package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.nodes.*
import edu.duke.bartesaghi.micromon.services.JobData


object Nodes {

	private val infos = mapOf(
		SingleParticleRawDataNodeConfig to SingleParticleRawDataNode.Companion,
		SingleParticleRelionDataNodeConfig to SingleParticleRelionDataNode.Companion,
		SingleParticleImportDataNodeConfig to SingleParticleImportDataNode.Companion,
		SingleParticleSessionDataNodeConfig to SingleParticleSessionDataNode.Companion,
		SingleParticlePreprocessingNodeConfig to SingleParticlePreprocessingNode.Companion,
		SingleParticlePurePreprocessingNodeConfig to SingleParticlePurePreprocessingNode.Companion,
		SingleParticleDenoisingNodeConfig to SingleParticleDenoisingNode.Companion,
		SingleParticlePickingNodeConfig to SingleParticlePickingNode.Companion,
		SingleParticleDrgnNodeConfig to SingleParticleDrgnNode.Companion,
		SingleParticleCoarseRefinementNodeConfig to SingleParticleCoarseRefinementNode.Companion,
		SingleParticleFineRefinementNodeConfig to SingleParticleFineRefinementNode.Companion,
		SingleParticleFlexibleRefinementNodeConfig to SingleParticleFlexibleRefinementNode.Companion,
		SingleParticlePostprocessingNodeConfig to SingleParticlePostprocessingNode.Companion,
		SingleParticleMaskingNodeConfig to SingleParticleMaskingNode.Companion,
		TomographyRawDataNodeConfig to TomographyRawDataNode.Companion,
		TomographyRelionDataNodeConfig to TomographyRelionDataNode.Companion,
		TomographyImportDataNodeConfig to TomographyImportDataNode.Companion,
		TomographySessionDataNodeConfig to TomographySessionDataNode.Companion,
		TomographyPreprocessingNodeConfig to TomographyPreprocessingNode.Companion,
		TomographyPurePreprocessingNodeConfig to TomographyPurePreprocessingNode.Companion,
		TomographyDenoisingNodeConfig to TomographyDenoisingNode.Companion,
		TomographyPickingNodeConfig to TomographyPickingNode.Companion,
		TomographyPickingOpenNodeConfig to TomographyPickingOpenNode.Companion,
		TomographyPickingClosedNodeConfig to TomographyPickingClosedNode.Companion,
		TomographyPickingModelNodeConfig to TomographyPickingModelNode.Companion,
		TomographyDrgnNodeConfig to TomographyDrgnNode.Companion,
		TomographyCoarseRefinementNodeConfig to TomographyCoarseRefinementNode.Companion,
		TomographyFineRefinementNodeConfig to TomographyFineRefinementNode.Companion,
		TomographyMovieCleaningNodeConfig to TomographyMovieCleaningNode.Companion,
		TomographyFlexibleRefinementNodeConfig to TomographyFlexibleRefinementNode.Companion
	)

	fun clientInfo(config: NodeConfig): NodeClientInfo =
		infos[config] ?: throw NoSuchElementException("client info not set for node configuration: ${config.id}")

	private val infosByJob = infos.values
		.filter { it.jobClass != null }
		.associateBy { it.jobClass }

	fun clientInfo(job: JobData): NodeClientInfo =
		infosByJob[job::class] ?: throw NoSuchElementException("client info not set for job display: ${job::class}")
}


val NodeConfig.clientInfo get() = Nodes.clientInfo(this)
val JobData.clientInfo get() = Nodes.clientInfo(this)
