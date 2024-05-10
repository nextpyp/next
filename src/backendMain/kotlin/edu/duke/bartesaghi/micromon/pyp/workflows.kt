package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.nodes.NodeConfigs
import edu.duke.bartesaghi.micromon.nodes.SingleParticleSessionDataNodeConfig
import edu.duke.bartesaghi.micromon.nodes.TomographySessionDataNodeConfig
import edu.duke.bartesaghi.micromon.nodes.Workflow
import org.tomlj.Toml
import org.tomlj.TomlTable
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText


object Workflows {

	val workflows: Map<Int,Workflow> get() = _workflows
	private val _workflows = HashMap<Int,Workflow>()


	fun init() {
		for (dir in Backend.config.web.workflowDirs) {
			for (file in dir.listFiles()) {
				if (file.isRegularFile()) {
					Backend.log.info("Reading workflow: $file")
					readToml(file.readText())
				}
			}
		}
	}

	fun readToml(toml: String): Workflow {

		// read the workflow TOML file
		val doc = Toml.parse(toml)
		if (doc.hasErrors()) {
			throw TomlParseException("TOML parsing failure:\n${doc.errors().joinToString("\n")}")
		}

		// read the workflow properties
		val workflowName = doc.getStringOrThrow("name")
		val workflowDescription = doc.getString("description")

		// read the blocks
		val blocks = ArrayList<Workflow.BlockInstance>()
		val blockInstanceIds = HashSet<String>()
		val blocksDoc = doc.getTableOrThrow("blocks")
		for (instanceId in blocksDoc.keySetInOrder()) {
			val blockDoc = blocksDoc.getTableOrThrow(instanceId)

			blockInstanceIds.add(instanceId)

			val blockId = blockDoc.getStringOrThrow("blockId")
			val name = blockDoc.getString("name")
			val parentBlockId = blockDoc.getString("parent")

			// validate the blockId
			if (NodeConfigs[blockId] == null) {
				throw NoSuchElementException("No node with id=$blockId")
			}

			// HACKHACK: certain blocks can't be imported in a workflow
			// until we make new user data collection mechanisms (like getting the session id)
			when (blockId) {
				SingleParticleSessionDataNodeConfig.ID,
				TomographySessionDataNodeConfig.ID -> throw UnsupportedOperationException(
						"Session import blocks are currently unsupported in workflows"
						+ " until we create a mechanism to ask for the session id,"
						+ " which isn't a PYP argument and so can't be asked for using the current tools."
					)
			}

			// make sure the parent block has been already defined
			if (parentBlockId != null && parentBlockId !in blockInstanceIds) {
				throw NoSuchElementException(
					"Parent block `$parentBlockId` has not been defined yet."
					+ " Please define it above the definition for block `$blockId`."
				)
			}

			// read the args values
			val askArgs = ArrayList<String>()
			val argValues = ArgValues(Backend.pypArgs)
			blockDoc.getTable("args")?.let { argsDoc ->
				for (argFullId in argsDoc.keySetInOrder()) {
					val arg = Backend.pypArgs.argOrThrow(argFullId)
					val value = argsDoc.get(argFullId)

					when (value) {

						is TomlTable ->
							if (value.getBoolean("ask") == true) {
								askArgs.add(argFullId)
							} else {
								throw IllegalArgumentException("unrecognized special value for argument $argFullId")
							}

						else -> argValues[arg] = value.translateTomlValueForReading(arg)
					}
				}
			}

			blocks.add(Workflow.BlockInstance(instanceId, blockId, name, parentBlockId, askArgs, argValues.toToml()))
		}

		// finally, save the workflow
		synchronized(workflows) {
			val workflow = Workflow(
				id = workflows.size + 1,
				name = workflowName,
				description = workflowDescription,
				blocks = blocks
			)
			_workflows[workflow.id] = workflow
			return workflow
		}
	}
}


// TODO: convert this to kotest
/** a sort of crude unit test */
fun main() {

	val workflow = Workflows.readToml("""
		|
		|name = "The main tutorial"
		|description = "Learn all about nextPYP in this tutorial!"
		|
		|# comments here are useful to share info with other workflow authors looking at this file
		|[blocks.rawdata]
		|blockId = "sp-rawdata"
		|name = "Raw Data"
		|
		|[blocks.rawdata.args]
		|data_path = { ask = true }
		|scope_pixel = 1
		|scope_voltage = 300
		|
		|
		|[blocks.preprocessing]
		|blockId = "sp-preprocessing"
		|name = "Pre-processing"
		|parent = "rawdata"
		|
		|[blocks.preprocessing.args]
		|detect_rad = 75
		|detect_method = "all"
		|
		|
		|[blocks.refinement]
		|blockId = "sp-coarse-refinement"
		|name = "Refinement"
		|parent = "preprocessing"
		|
		|[blocks.refinement.args]
		|particle_mw = 75
		|particle_rad = 75
		|extract_box = 96
		|refine_model = { ask = true }
		|
	""".trimMargin())

	assertEq(workflow.id, 1)
	assertEq(workflow.name, "The main tutorial")
	assertEq(workflow.description != null && workflow.description.startsWith("Learn "), true)

	workflow.blocks[0].apply {
		assertEq(instanceId, "rawdata")
		assertEq(blockId, "sp-rawdata")
		assertEq(name, "Raw Data")
		assertEq(askArgs, listOf("data_path"))
		assertEq(argValues, """
			|scope_pixel = 1.0
			|scope_voltage = 300.0
		""".trimMargin())
	}

	workflow.blocks[1].apply {
		assertEq(instanceId, "preprocessing")
		assertEq(blockId, "sp-preprocessing")
		assertEq(parentInstanceId, "rawdata")
	}

	workflow.blocks[2].apply {
		assertEq(instanceId, "refinement")
		assertEq(blockId, "sp-coarse-refinement")
		assertEq(parentInstanceId, "preprocessing")
	}
}
