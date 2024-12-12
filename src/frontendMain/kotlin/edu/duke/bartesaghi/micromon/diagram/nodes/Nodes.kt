package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.nodes.*
import edu.duke.bartesaghi.micromon.pyp.ArgGroup
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.toArgValues
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
		TomographyImportDataPureNodeConfig to TomographyImportDataPureNode.Companion,
		TomographySessionDataNodeConfig to TomographySessionDataNode.Companion,
		TomographyPreprocessingNodeConfig to TomographyPreprocessingNode.Companion,
		TomographyPurePreprocessingNodeConfig to TomographyPurePreprocessingNode.Companion,
		TomographyDenoisingTrainingNodeConfig to TomographyDenoisingTrainingNode.Companion,
		TomographyDenoisingEvalNodeConfig to TomographyDenoisingEvalNode.Companion,
		TomographyPickingNodeConfig to TomographyPickingNode.Companion,
		TomographySegmentationOpenNodeConfig to TomographySegmentationOpenNode.Companion,
		TomographySegmentationClosedNodeConfig to TomographySegmentationClosedNode.Companion,
		TomographyPickingOpenNodeConfig to TomographyPickingOpenNode.Companion,
		TomographyPickingClosedNodeConfig to TomographyPickingClosedNode.Companion,
		TomographyMiloTrainNodeConfig to TomographyMiloTrainNode.Companion,
		TomographyMiloEvalNodeConfig to TomographyMiloEvalNode.Companion,
		TomographyParticlesTrainNodeConfig to TomographyParticlesTrainNode.Companion,
		TomographyParticlesEvalNodeConfig to TomographyParticlesEvalNode.Companion,
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
