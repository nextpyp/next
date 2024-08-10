package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.jobs.JobInfo
import edu.duke.bartesaghi.micromon.jobs.TomographyRawDataJob
import edu.duke.bartesaghi.micromon.jobs.jobInfo
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.nodes.NodeConfigs


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
				+ TomographyRawDataJob.mockArgs()
		)
}


private val Block.nodeConfig: NodeConfig? get() =
	NodeConfigs.findByConfigId(blockId)


private val JobInfo.mockGroupId: String get() =
	"${config.id.replace('-', '_')}_mock"


private fun TomographyRawDataJob.Companion.mockArgs(): List<Arg> = listOf(
	Arg(
		groupId = mockGroupId,
		argId = "image_size",
		name = "Image Size",
		description = "",
		type = ArgType.TInt(),
		default = ArgValue.VInt(512)
	)
)
