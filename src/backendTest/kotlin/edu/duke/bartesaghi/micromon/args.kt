package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.pyp.*


class ArgExtensions {

	private val groups = ArrayList<GroupExtension>()
	private val blockGroups = HashMap<String,List<String>>()

	inner class GroupExtension(id: String) {

		private val _args = ArrayList<Arg>()
		val args: List<Arg> get() = _args

		val group = ArgGroup(
			"test_$id",
			name = "Test Group",
			description = "This is a test"
		)

		fun arg(
			id: String,
			type: ArgType,
			default: ArgValue? = null,
			copyToNewBlock: Boolean = true
		) = Arg(
			group.groupId,
			id,
			"Test Arg",
			"This is a test",
			type = type,
			default = default,
			copyToNewBlock = copyToNewBlock
		)
			.also { _args.add(it) }
	}

	fun group(id: String) =
		GroupExtension(id)
			.also { groups.add(it) }

	fun extendBlock(config: NodeConfig, vararg groups: GroupExtension) {
		blockGroups[config.configId] = groups.map { it.group.groupId }
	}

	fun apply(args: Args) = Args(
		blocks = args.blocks.map { block ->
			blockGroups[block.blockId]
				?.let { block.copy(groupIds = block.groupIds + it) }
				?: block
		},
		groups = args.groups + groups.map { it.group },
		args = args.args + groups.flatMap { it.args }
	)
}
