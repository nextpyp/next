package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.nodes.NodeConfigs
import edu.duke.bartesaghi.micromon.sessions.Session
import edu.duke.bartesaghi.micromon.sessions.SingleParticleSession
import edu.duke.bartesaghi.micromon.sessions.TomographySession


object MockPyp {

	fun combineArgs(args: Args): Args =
		Args(
			blocks = args.blocks.map {
				val nodeConfig = it.nodeConfig
					?: return@map it
				// add a new mock group to each block
				Block(
					blockId = it.blockId,
					name = it.name,
					description = it.description,
					groupIds = it.groupIds + listOf(nodeConfig.jobInfo.mockGroupId)
				)
			},
			groups = args.groups + args.blocks.mapNotNull {
				val nodeConfig = it.nodeConfig
					?: return@mapNotNull null
				ArgGroup(
					groupId = nodeConfig.jobInfo.mockGroupId,
					name = "Mock",
					description = "Arguments for Mock Pyp"
				)
			},
			args = args.args
				.map {
					// no pyp args are actually required in mock mode
					Arg(
						groupId = it.groupId,
						argId = it.argId,
						name = it.name,
						description = it.description,
						type = it.type,
						required = false,
						default = it.default,
						input = it.input,
						hidden = it.hidden,
						target = it.target,
						copyToNewBlock = it.copyToNewBlock,
						advanced = it.advanced,
						condition = it.condition
					)
				}
				// blocks
				+ TomographyRawDataMockArgs.all
				+ TomographyPreprocessingMockArgs.all
				+ TomographyPurePreprocessingMockArgs.all
				+ TomographyPickingMockArgs.all
				+ TomographySegmentationClosedMockArgs.all
				+ TomographyPickingOpenMockArgs.all
				+ TomographyPickingClosedMockArgs.all
				+ TomographyParticlesEvalMockArgs.all
				+ TomographyMiloEvalMockArgs.all
				+ TomographySessionDataMockArgs.all
				+ TomographyImportDataMockArgs.all
				+ TomographyRelionDataMockArgs.all
				+ SingleParticleRawDataMockArgs.all
				+ SingleParticlePreprocessingMockArgs.all
				+ SingleParticlePurePreprocessingMockArgs.all
				+ SingleParticlePickingMockArgs.all
				// sessions
				+ TomographySessionMockArgs.all
				+ SingleParticleSessionMockArgs.all
		)
}


private val Block.nodeConfig: NodeConfig? get() =
	NodeConfigs.findByConfigId(blockId)


private val JobInfo.mockGroupId: String get() =
	"${config.id.replace('-', '_')}_mock"

open class JobMockArgs(val jobInfo: JobInfo) {
	
	private val _all = ArrayList<Arg>()
	val all: List<Arg> get() = _all
	
	fun mockArg(id: String, type: ArgType, default: ArgValue? = null): Arg =
		Arg(
			groupId = jobInfo.mockGroupId,
			argId = id,
			name = id,
			description = "",
			type = type,
			default = default
		)
			.also { _all.add(it) }
}


object TomographyRawDataMockArgs : JobMockArgs(TomographyRawDataJob) {
	val imageSize = mockArg("image_size", ArgType.TInt(), ArgValue.VInt(512))
}


object TomographyPreprocessingMockArgs : JobMockArgs(TomographyPreprocessingJob) {
	val numTiltSeries = mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4))
	val numTilts = mockArg("num_tilts", ArgType.TInt(), ArgValue.VInt(4))
	val tomogramWidth = mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192))
	val tomogramHeight = mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192))
	val numVirions = mockArg("num_virions", ArgType.TInt(), ArgValue.VInt(5))
	val numSpiked = mockArg("num_spikes", ArgType.TInt(), ArgValue.VInt(10))
	val tiltAngleMagnitude = mockArg("tilt_angle_magnitude", ArgType.TInt(), ArgValue.VInt(45))
}


object TomographyPurePreprocessingMockArgs : JobMockArgs(TomographyPurePreprocessingJob) {
	val numTiltSeries = mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4))
	val numTilts = mockArg("num_tilts", ArgType.TInt(), ArgValue.VInt(4))
	val tomogramWidth = mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192))
	val tomogramHeight = mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192))
	val tiltAngleMagnitude = mockArg("tilt_angle_magnitude", ArgType.TInt(), ArgValue.VInt(45))
	val splitMode = mockArg("split_mode", ArgType.TBool(), ArgValue.VBool(false))
}


object TomographyPickingMockArgs : JobMockArgs(TomographyPickingJob) {
	val numTiltSeries = mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4))
	val tomogramWidth = mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192))
	val tomogramHeight = mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192))
	val numParticles = mockArg("num_particles", ArgType.TInt(), ArgValue.VInt(20))
	val threshold = mockArg("threshold", ArgType.TInt(), ArgValue.VInt(1))
}


object TomographySegmentationClosedMockArgs : JobMockArgs(TomographySegmentationClosedJob) {
	val numTiltSeries = mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4))
	val tomogramWidth = mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192))
	val tomogramHeight = mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192))
	val virionRadius = mockArg("virion_radius", ArgType.TFloat(), ArgValue.VFloat(1000.0))
	val numParticles = mockArg("num_particles", ArgType.TInt(), ArgValue.VInt(20))
	val threshold = mockArg("threshold", ArgType.TInt(), ArgValue.VInt(1))
}


object TomographyPickingOpenMockArgs : JobMockArgs(TomographyPickingOpenJob) {
	val numTiltSeries = mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4))
	val tomogramWidth = mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192))
	val tomogramHeight = mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192))
	val numParticles = mockArg("num_particles", ArgType.TInt(), ArgValue.VInt(20))
}


object TomographyPickingClosedMockArgs : JobMockArgs(TomographyPickingClosedJob) {
	val numTiltSeries = mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4))
	val tomogramWidth = mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192))
	val tomogramHeight = mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192))
	val numVirions = mockArg("num_virions", ArgType.TInt(), ArgValue.VInt(20))
	val virionRadius = mockArg("virion_radius", ArgType.TFloat(), ArgValue.VFloat(1000.0))
	val numSpikes = mockArg("num_spikes", ArgType.TInt(), ArgValue.VInt(40))
	val spikeRadius = mockArg("spike_radius", ArgType.TFloat(), ArgValue.VFloat(500.0))
}


object TomographyParticlesEvalMockArgs : JobMockArgs(TomographyParticlesEvalJob) {
	val numTiltSeries = mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4))
	val tomogramWidth = mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192))
	val tomogramHeight = mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192))
	val numParticles = mockArg("num_particles", ArgType.TInt(), ArgValue.VInt(20))
	val particleRadius = mockArg("particle_radius", ArgType.TFloat(), ArgValue.VFloat(500.0))
}


object TomographyMiloEvalMockArgs : JobMockArgs(TomographyMiloEvalJob) {
	val numTiltSeries = mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4))
	val tomogramWidth = mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192))
	val tomogramHeight = mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192))
}


object TomographySessionDataMockArgs : JobMockArgs(TomographySessionDataJob) {
	val numTiltSeries = mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4))
	val numTilts = mockArg("num_tilts", ArgType.TInt(), ArgValue.VInt(4))
	val tomogramWidth = mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192))
	val tomogramHeight = mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192))
	val numVirions = mockArg("num_virions", ArgType.TInt(), ArgValue.VInt(5))
	val numSpikes = mockArg("num_spikes", ArgType.TInt(), ArgValue.VInt(10))
	val tiltAngleMagnitde = mockArg("tilt_angle_magnitude", ArgType.TInt(), ArgValue.VInt(45))
}


object TomographyImportDataMockArgs : JobMockArgs(TomographyImportDataJob) {
	val numTiltSeries = mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4))
	val numTilts = mockArg("num_tilts", ArgType.TInt(), ArgValue.VInt(4))
	val tomogramWidth = mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192))
	val tomogramHeight = mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192))
	val numVirions = mockArg("num_virions", ArgType.TInt(), ArgValue.VInt(5))
	val numSpikes = mockArg("num_spikes", ArgType.TInt(), ArgValue.VInt(10))
	val tiltAngleMagnitde = mockArg("tilt_angle_magnitude", ArgType.TInt(), ArgValue.VInt(45))
}


object TomographyRelionDataMockArgs : JobMockArgs(TomographyRelionDataJob) {
	val imageSize = mockArg("image_size", ArgType.TInt(), ArgValue.VInt(512))
}


object SingleParticleRawDataMockArgs : JobMockArgs(SingleParticleRawDataJob) {
	val imageSize = mockArg("image_size", ArgType.TInt(), ArgValue.VInt(512))
}


object SingleParticlePreprocessingMockArgs : JobMockArgs(SingleParticlePreprocessingJob) {
	val numMicrographs = mockArg("num_micrographs", ArgType.TInt(), ArgValue.VInt(4))
	val micrographWidth = mockArg("micrograph_width", ArgType.TInt(), ArgValue.VInt(8192))
	val micrigraphHeight = mockArg("micrograph_height", ArgType.TInt(), ArgValue.VInt(8192))
	val numParticles = mockArg("num_particles", ArgType.TInt(), ArgValue.VInt(20))
	val particleRadius = mockArg("particle_radius", ArgType.TFloat(), ArgValue.VFloat(100.0))
}


object SingleParticlePurePreprocessingMockArgs : JobMockArgs(SingleParticlePurePreprocessingJob) {
	val numMicrographs = mockArg("num_micrographs", ArgType.TInt(), ArgValue.VInt(4))
	val micrographWidth = mockArg("micrograph_width", ArgType.TInt(), ArgValue.VInt(8192))
	val micrigraphHeight = mockArg("micrograph_height", ArgType.TInt(), ArgValue.VInt(8192))
}


object SingleParticlePickingMockArgs : JobMockArgs(SingleParticlePickingJob) {
	val numMicrographs = mockArg("num_micrographs", ArgType.TInt(), ArgValue.VInt(4))
	val micrographWidth = mockArg("micrograph_width", ArgType.TInt(), ArgValue.VInt(8192))
	val micrigraphHeight = mockArg("micrograph_height", ArgType.TInt(), ArgValue.VInt(8192))
	val numParticles = mockArg("num_particles", ArgType.TInt(), ArgValue.VInt(20))
	val particleRadius = mockArg("particle_radius", ArgType.TFloat(), ArgValue.VFloat(100.0))
}


private val Session.Type.mockGroupId: String get() =
	"${configId.replace('-', '_')}_mock"

open class SessionMockArgs(val sessionType: Session.Type) {

	private val _all = ArrayList<Arg>()
	val all: List<Arg> get() = _all

	fun mockArg(id: String, type: ArgType, default: ArgValue? = null): Arg =
		Arg(
			groupId = sessionType.mockGroupId,
			argId = id,
			name = id,
			description = "",
			type = type,
			default = default
		)
			.also { _all.add(it) }
}


object TomographySessionMockArgs : SessionMockArgs(TomographySession) {
	val numTiltSeries = mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4))
	val numTilts = mockArg("num_tilts", ArgType.TInt(), ArgValue.VInt(4))
	val tomogramWidth = mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192))
	val tomogramHeight = mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192))
	val numVirions = mockArg("num_virions", ArgType.TInt(), ArgValue.VInt(5))
	val numSpikes = mockArg("num_spikes", ArgType.TInt(), ArgValue.VInt(10))
	val tiltAngleMagnitde = mockArg("tilt_angle_magnitude", ArgType.TInt(), ArgValue.VInt(45))
}


object SingleParticleSessionMockArgs : SessionMockArgs(SingleParticleSession) {
	val numMicrographs = mockArg("num_micrographs", ArgType.TInt(), ArgValue.VInt(4))
	val micrographWidth = mockArg("micrograph_width", ArgType.TInt(), ArgValue.VInt(8192))
	val micrigraphHeight = mockArg("micrograph_height", ArgType.TInt(), ArgValue.VInt(8192))
	val numParticles = mockArg("num_particles", ArgType.TInt(), ArgValue.VInt(20))
	val particleRadius = mockArg("particle_radius", ArgType.TFloat(), ArgValue.VFloat(100.0))
}
