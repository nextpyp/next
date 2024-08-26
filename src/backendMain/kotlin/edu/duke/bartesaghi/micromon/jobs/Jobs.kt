package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.nodes.*


object Jobs {

	// map nodes to jobs on the backend
	private val infos = mapOf(
		SingleParticleRawDataNodeConfig to SingleParticleRawDataJob.Companion,
		SingleParticleRelionDataNodeConfig to SingleParticleRelionDataJob.Companion,
		SingleParticleImportDataNodeConfig to SingleParticleImportDataJob.Companion,
		SingleParticleSessionDataNodeConfig to SingleParticleSessionDataJob.Companion,
		SingleParticlePreprocessingNodeConfig to SingleParticlePreprocessingJob.Companion,
		SingleParticlePurePreprocessingNodeConfig to SingleParticlePurePreprocessingJob.Companion,
		SingleParticleDenoisingNodeConfig to SingleParticleDenoisingJob.Companion,
		SingleParticlePickingNodeConfig to SingleParticlePickingJob.Companion,
		SingleParticleDrgnNodeConfig to SingleParticleDrgnJob.Companion,
		SingleParticleCoarseRefinementNodeConfig to SingleParticleCoarseRefinementJob.Companion,
		SingleParticleFineRefinementNodeConfig to SingleParticleFineRefinementJob.Companion,
		SingleParticleFlexibleRefinementNodeConfig to SingleParticleFlexibleRefinementJob.Companion,
		SingleParticlePostprocessingNodeConfig to SingleParticlePostprocessingJob.Companion,
		SingleParticleMaskingNodeConfig to SingleParticleMaskingJob.Companion,
		TomographyRawDataNodeConfig to TomographyRawDataJob.Companion,
		TomographyRelionDataNodeConfig to TomographyRelionDataJob.Companion,
		TomographyImportDataNodeConfig to TomographyImportDataJob.Companion,
		TomographySessionDataNodeConfig to TomographySessionDataJob.Companion,
		TomographyPreprocessingNodeConfig to TomographyPreprocessingJob.Companion,
		TomographyPurePreprocessingNodeConfig to TomographyPurePreprocessingJob.Companion,
		TomographyDenoisingTrainingNodeConfig to TomographyDenoisingTrainingJob.Companion,
		TomographyDenoisingEvalNodeConfig to TomographyDenoisingEvalJob.Companion,
		TomographyDenoisingNodeConfig to TomographyDenoisingJob.Companion,
		TomographyPickingNodeConfig to TomographyPickingJob.Companion,
		TomographySegmentationOpenNodeConfig to TomographySegmentationOpenJob.Companion,
		TomographySegmentationClosedNodeConfig to TomographySegmentationClosedJob.Companion,
		TomographyPickingOpenNodeConfig to TomographyPickingOpenJob.Companion,
		TomographyPickingClosedNodeConfig to TomographyPickingClosedJob.Companion,
		TomographyMiloTrainNodeConfig to TomographyMiloTrainJob.Companion,
		TomographyMiloEvalNodeConfig to TomographyMiloEvalJob.Companion,
		TomographyParticlesTrainNodeConfig to TomographyParticlesTrainJob.Companion,
		TomographyParticlesEvalNodeConfig to TomographyParticlesEvalJob.Companion,
		TomographyDrgnNodeConfig to TomographyDrgnJob.Companion,
		TomographyCoarseRefinementNodeConfig to TomographyCoarseRefinementJob.Companion,
		TomographyFineRefinementNodeConfig to TomographyFineRefinementJob.Companion,
		TomographyMovieCleaningNodeConfig to TomographyMovieCleaningJob.Companion,
		TomographyFlexibleRefinementNodeConfig to TomographyFlexibleRefinementJob.Companion
	)

	fun info(config: NodeConfig): JobInfo =
		infos[config] ?: throw NoSuchElementException("job info not set for node configuration: ${config.id}")
}

val NodeConfig.jobInfo get() = Jobs.info(this)
