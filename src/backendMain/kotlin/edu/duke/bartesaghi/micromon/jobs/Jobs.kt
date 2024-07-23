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
		TomographySegmentationNodeConfig to TomographySegmentationJob.Companion,
		TomographyPickingNodeConfig to TomographyPickingJob.Companion,
		TomographyDenoisingNodeConfig to TomographyDenoisingJob.Companion,
		TomographyCoarseRefinementNodeConfig to TomographyCoarseRefinementJob.Companion,
		TomographyFineRefinementNodeConfig to TomographyFineRefinementJob.Companion,
		TomographyMovieCleaningNodeConfig to TomographyMovieCleaningJob.Companion,
		TomographyFlexibleRefinementNodeConfig to TomographyFlexibleRefinementJob.Companion
	)

	fun info(config: NodeConfig): JobInfo =
		infos[config] ?: throw NoSuchElementException("job info not set for node configuration: ${config.id}")
}

val NodeConfig.jobInfo get() = Jobs.info(this)
