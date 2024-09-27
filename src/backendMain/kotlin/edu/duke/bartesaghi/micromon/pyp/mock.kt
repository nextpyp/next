package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.nodes.NodeConfigs
import edu.duke.bartesaghi.micromon.sessions.Session
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
					groupIds = it.groupIds + listOf(nodeConfig.jobInfo.mockGroupId),
					forwardedGroupIds = it.forwardedGroupIds
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
				+ TomographyRawDataJob.mockArgs()
				+ TomographyPreprocessingJob.mockArgs()
				+ TomographyPurePreprocessingJob.mockArgs()
				+ TomographyDenoisingJob.mockArgs()
				+ TomographyPickingJob.mockArgs()
				+ TomographySegmentationClosedJob.mockArgs()
				+ TomographyPickingOpenJob.mockArgs()
				+ TomographyPickingClosedJob.mockArgs()
				+ TomographyParticlesEvalJob.mockArgs()
				+ TomographyMiloEvalJob.mockArgs()
				+ TomographySessionDataJob.mockArgs()
				+ TomographyImportDataJob.mockArgs()
				+ TomographyRelionDataJob.mockArgs()
				+ SingleParticleRawDataJob.mockArgs()
				+ SingleParticlePreprocessingJob.mockArgs()
				// sessions
				+ TomographySession.mockArgs()
		)
}


private val Block.nodeConfig: NodeConfig? get() =
	NodeConfigs.findByConfigId(blockId)


private val JobInfo.mockGroupId: String get() =
	"${config.id.replace('-', '_')}_mock"


private fun JobInfo.mockArg(id: String, type: ArgType, default: ArgValue? = null): Arg =
	Arg(
		groupId = mockGroupId,
		argId = id,
		name = id,
		description = "",
		type = type,
		default = default
	)


private fun TomographyRawDataJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("image_size", ArgType.TInt(), ArgValue.VInt(512))
)


private fun TomographyPreprocessingJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("num_tilts", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("num_virions", ArgType.TInt(), ArgValue.VInt(5)),
	mockArg("num_spikes", ArgType.TInt(), ArgValue.VInt(10)),
	mockArg("tilt_angle_magnitude", ArgType.TInt(), ArgValue.VInt(45))
)


private fun TomographyPurePreprocessingJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("num_tilts", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("tilt_angle_magnitude", ArgType.TInt(), ArgValue.VInt(45)),
	mockArg("split_mode", ArgType.TBool(), ArgValue.VBool(false))
)


private fun TomographyDenoisingJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192)),
)


private fun TomographyPickingJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("num_particles", ArgType.TInt(), ArgValue.VInt(20))
)


private fun TomographySegmentationClosedJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("virion_radius", ArgType.TFloat(), ArgValue.VFloat(1000.0)),
	mockArg("num_particles", ArgType.TInt(), ArgValue.VInt(20))
)


private fun TomographyPickingOpenJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("num_particles", ArgType.TInt(), ArgValue.VInt(20))
)


private fun TomographyPickingClosedJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("num_virions", ArgType.TInt(), ArgValue.VInt(20)),
	mockArg("virion_radius", ArgType.TFloat(), ArgValue.VFloat(1000.0)),
	mockArg("num_spikes", ArgType.TInt(), ArgValue.VInt(40)),
	mockArg("spike_radius", ArgType.TFloat(), ArgValue.VFloat(500.0))
)


private fun TomographyParticlesEvalJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("num_particles", ArgType.TInt(), ArgValue.VInt(20)),
	mockArg("particle_radius", ArgType.TFloat(), ArgValue.VFloat(500.0))
)


private fun TomographyMiloEvalJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("num_particles", ArgType.TInt(), ArgValue.VInt(20))
)


private fun TomographySessionDataJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("num_tilts", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("num_virions", ArgType.TInt(), ArgValue.VInt(5)),
	mockArg("num_spikes", ArgType.TInt(), ArgValue.VInt(10)),
	mockArg("tilt_angle_magnitude", ArgType.TInt(), ArgValue.VInt(45))
)


private fun TomographyImportDataJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("num_tilts", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("num_virions", ArgType.TInt(), ArgValue.VInt(5)),
	mockArg("num_spikes", ArgType.TInt(), ArgValue.VInt(10)),
	mockArg("tilt_angle_magnitude", ArgType.TInt(), ArgValue.VInt(45))
)


private fun TomographyRelionDataJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("image_size", ArgType.TInt(), ArgValue.VInt(512))
)


private fun SingleParticleRawDataJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("image_size", ArgType.TInt(), ArgValue.VInt(512))
)


private fun SingleParticlePreprocessingJob.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("num_micrographs", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("micrograph_width", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("micrograph_height", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("num_particles", ArgType.TInt(), ArgValue.VInt(20)),
	mockArg("particle_radius", ArgType.TFloat(), ArgValue.VFloat(100.0))
)


private val Session.Type.mockGroupId: String get() =
	"${configId.replace('-', '_')}_mock"

private fun Session.Type.mockArg(id: String, type: ArgType, default: ArgValue? = null): Arg =
	Arg(
		groupId = mockGroupId,
		argId = id,
		name = id,
		description = "",
		type = type,
		default = default
	)

private fun TomographySession.Companion.mockArgs(): List<Arg> = listOf(
	mockArg("num_tilt_series", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("num_tilts", ArgType.TInt(), ArgValue.VInt(4)),
	mockArg("tomogram_width", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("tomogram_height", ArgType.TInt(), ArgValue.VInt(8192)),
	mockArg("num_virions", ArgType.TInt(), ArgValue.VInt(5)),
	mockArg("num_spikes", ArgType.TInt(), ArgValue.VInt(10)),
	mockArg("tilt_angle_magnitude", ArgType.TInt(), ArgValue.VInt(45))
)
