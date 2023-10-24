package edu.duke.bartesaghi.micromon.nodes

import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import kotlinx.serialization.Serializable


@Serializable
data class Workflow(
	val id: Int,
	val name: String,
	val description: String?,
	/** listed in block insertion order */
	val blocks: List<BlockInstance>
) {

	@Serializable
	data class BlockInstance(
		/** uniquely identifies the block instance in the workflow */
		val instanceId: String,
		/** references an ID field from the hard-coded set of ___NodeConfig objects */
		val blockId: String,
		val name: String?,
		val parentInstanceId: String?,
		val askArgs: List<String>,
		val argValues: ArgValuesToml
	)
}
