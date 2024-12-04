package edu.duke.bartesaghi.micromon.blocks

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.nodes.SingleParticlePickingNodeConfig
import edu.duke.bartesaghi.micromon.nodes.SingleParticlePurePreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.nodes.SingleParticleRawDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe


@EnabledIf(RuntimeEnvironment.Website::class)
class TestForwardedGroups : FunSpec({

	// start one database for all of these tests
	val database = autoClose(EphemeralMongo.start())
	autoClose(database.install())

	// TODO: refactor this into a shared space?
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

	// define some synthetic args for testing
	val argExtensions = ArgExtensions()
	val upstreamGroup = argExtensions.group("upstream")
	val argInt = upstreamGroup.arg("argInt", ArgType.TInt(), default = ArgValue.VInt(1))
	val argStr = upstreamGroup.arg("argStr", ArgType.TStr())
	val downstreamGroup = argExtensions.group("downstream")
	val argIntD = downstreamGroup.arg("argInt", ArgType.TInt(), default = ArgValue.VInt(5))
	val argStrD = downstreamGroup.arg("argStr", ArgType.TStr(), default = ArgValue.VStr("bar"))
	val sharedGroup = argExtensions.group("shared")
	val argIntS = sharedGroup.arg("argInt", ArgType.TInt())
	val argIntSNoCopy = sharedGroup.arg("argIntNoCopy", ArgType.TInt(), copyToNewBlock=false)
	val argIntSDefaulted = sharedGroup.arg("argIntDefaulted", ArgType.TInt(), default = ArgValue.VInt(42))
	argExtensions.extendBlock(SingleParticleRawDataNodeConfig, upstreamGroup, sharedGroup)
	argExtensions.extendBlock(SingleParticlePurePreprocessingNodeConfig, downstreamGroup, sharedGroup)
	argExtensions.extendBlock(SingleParticlePickingNodeConfig, sharedGroup)

	// start one website for all of these tests
	val econfig = autoClose(EphemeralConfig {
		pypArgs = argExtensions.apply(pypArgs)
	})
	autoClose(econfig.install())
	val website = autoClose(EphemeralWebsite())


	test("forwards groups") {
		website.createProjectAndListen { project, ws ->

			// make a raw data block, with some arg values
			val rawDataArgs = SingleParticleRawDataArgs(econfig.argsToml {
				this[argInt] = 5
				this[argStr] = "foo"
			})
			val rawDataJob = website.importBlock(project, ISingleParticleRawDataService::import, rawDataArgs)

			// make a downstream preprocessing block, with no args
			val preprocessingArgs = SingleParticlePurePreprocessingArgs("")
			val preprocessingJob = website.addBlock(project, rawDataJob, ISingleParticlePurePreprocessingService::addNode, preprocessingArgs)

			// run the project
			val runResult = website.runProject(project, listOf(rawDataJob, preprocessingJob), ws)
			runResult.clusterJobs.size shouldBe 2

			// the raw data block should have the arg values
			val rawDataValues = runResult.clusterJobs[0].pypValues(econfig)
			println("raw data args: $rawDataValues")
			rawDataValues[argInt] shouldBe 5
			rawDataValues[argStr] shouldBe "foo"

			// the preprocessing block should have them too
			val preprocessingValues = runResult.clusterJobs[1].pypValues(econfig)
			println("preprocessing args: $preprocessingValues")
			preprocessingValues[argInt] shouldBe 5
			preprocessingValues[argStr] shouldBe "foo"
		}
	}

	test("merges upstream with downstream") {
		website.createProjectAndListen { project, ws ->

			// make a raw data block, with some arg values
			val rawDataArgs = SingleParticleRawDataArgs(econfig.argsToml {
				this[argInt] = 5
				this[argStr] = "foo"
			})
			val rawDataJob = website.importBlock(project, ISingleParticleRawDataService::import, rawDataArgs)

			// make a downstream preprocessing block, with args
			val preprocessingArgs = SingleParticlePurePreprocessingArgs(econfig.argsToml {
				this[argIntD] = 7
				// leave argStrD at the default value
			})
			val preprocessingJob = website.addBlock(project, rawDataJob, ISingleParticlePurePreprocessingService::addNode, preprocessingArgs)

			// run the project
			val runResult = website.runProject(project, listOf(rawDataJob, preprocessingJob), ws)
			runResult.clusterJobs.size shouldBe 2

			// the raw data block should have the arg values
			val rawDataValues = runResult.clusterJobs[0].pypValues(econfig)
			println("raw data args: $rawDataValues")
			rawDataValues[argInt] shouldBe 5
			rawDataValues[argStr] shouldBe "foo"

			// the preprocessing block should have args from both blocks
			val preprocessingValues = runResult.clusterJobs[1].pypValues(econfig)
			println("preprocessing args: $preprocessingValues")
			preprocessingValues[argInt] shouldBe 5
			preprocessingValues[argStr] shouldBe "foo"
			preprocessingValues[argIntD] shouldBe 7
			preprocessingValues[argStrD] shouldBe null
		}
	}

	test("doesn't copy upstream shared args") {
		website.createProjectAndListen { project, ws ->

			// make a raw data block, with some shared arg values
			val rawDataArgs = SingleParticleRawDataArgs(econfig.argsToml {
				this[argIntS] = 5
				this[argIntSNoCopy] = 7
			})
			val rawDataJob = website.importBlock(project, ISingleParticleRawDataService::import, rawDataArgs)

			// make a downstream preprocessing block, with no args
			val preprocessingArgs = SingleParticlePurePreprocessingArgs("")
			val preprocessingJob = website.addBlock(project, rawDataJob, ISingleParticlePurePreprocessingService::addNode, preprocessingArgs)

			// run the project
			val runResult = website.runProject(project, listOf(rawDataJob, preprocessingJob), ws)
			runResult.clusterJobs.size shouldBe 2

			// the raw data block should have the arg values
			val rawDataValues = runResult.clusterJobs[0].pypValues(econfig)
			println("raw data args: $rawDataValues")
			rawDataValues[argIntS] shouldBe 5
			rawDataValues[argIntSNoCopy] shouldBe 7

			// the preprocessing block shouldn't have any shared arguments merged
			// (instead, the arg values should be copied into the new block's own arg values)
			val preprocessingValues = runResult.clusterJobs[1].pypValues(econfig)
			println("preprocessing args: $preprocessingValues")
			preprocessingValues[argIntS] shouldBe null
			preprocessingValues[argIntSNoCopy] shouldBe null
		}
	}

	test("gets copyable args") {
		website.createProjectAndListen { project, ws ->

			// make a raw data block, with some shared arg values
			val rawDataArgs = SingleParticleRawDataArgs(econfig.argsToml {
				this[argIntS] = 5
				this[argIntSNoCopy] = 7
			})
			val rawDataJob = website.importBlock(project, ISingleParticleRawDataService::import, rawDataArgs)

			// make a downstream preprocessing block, with only the copyable arg values
			val newArgValues = website.services.rpc(IProjectsService::newArgValues, website.getUserId(), project.projectId, rawDataJob.link(), SingleParticlePurePreprocessingNodeConfig.ID)
			val preprocessingArgs = SingleParticlePurePreprocessingArgs(newArgValues)
			val preprocessingJob = website.addBlock(project, rawDataJob, ISingleParticlePurePreprocessingService::addNode, preprocessingArgs)

			// run the project
			val runResult = website.runProject(project, listOf(rawDataJob, preprocessingJob), ws)
			runResult.clusterJobs.size shouldBe 2

			// the raw data block should have the arg values
			val rawDataValues = runResult.clusterJobs[0].pypValues(econfig)
			println("raw data args: $rawDataValues")
			rawDataValues[argIntS] shouldBe 5
			rawDataValues[argIntSNoCopy] shouldBe 7

			// the preprocessing block should only have the copied args
			val preprocessingValues = runResult.clusterJobs[1].pypValues(econfig)
			println("preprocessing args: $preprocessingValues")
			preprocessingValues[argIntS] shouldBe 5
			preprocessingValues[argIntSNoCopy] shouldBe null
		}
	}

	test("defaults can overwrite copied shared args") {
		website.createProjectAndListen { project, ws ->

			// make a raw data block, with a non-default shared arg value
			val rawDataArgs = SingleParticleRawDataArgs(econfig.argsToml {
				this[argIntSDefaulted] = 5
			})
			val rawDataJob = website.importBlock(project, ISingleParticleRawDataService::import, rawDataArgs)

			// make a downstream preprocessing block, with only default values
			val preprocessingArgs = SingleParticlePurePreprocessingArgs("")
			val preprocessingJob = website.addBlock(project, rawDataJob, ISingleParticlePurePreprocessingService::addNode, preprocessingArgs)

			// any new blocks downstream of that should only see default values
			val newArgValues = website.services.rpc(IProjectsService::newArgValues, website.getUserId(), project.projectId, preprocessingJob.link(), SingleParticlePickingNodeConfig.ID)
				.toArgValues(econfig.pypArgs)

			println("new args values: $newArgValues")
			newArgValues[argIntSDefaulted] shouldBe null
		}
	}

	test("doesn't forward implicit default values") {
		website.createProjectAndListen { project, ws ->

			// make a raw data block, with some arg values
			val rawDataArgs = SingleParticleRawDataArgs("")
			val rawDataJob = website.importBlock(project, ISingleParticleRawDataService::import, rawDataArgs)

			// make a downstream preprocessing block, with no args
			val preprocessingArgs = SingleParticlePurePreprocessingArgs("")
			val preprocessingJob = website.addBlock(project, rawDataJob, ISingleParticlePurePreprocessingService::addNode, preprocessingArgs)

			// run the project
			val runResult = website.runProject(project, listOf(rawDataJob, preprocessingJob), ws)
			runResult.clusterJobs.size shouldBe 2

			// the raw data block shouldn't have any values
			val rawDataValues = runResult.clusterJobs[0].pypValues(econfig)
			println("raw data args: $rawDataValues")
			rawDataValues[argInt] shouldBe null
			rawDataValues[argStr] shouldBe null

			// the preprocessing block shouldn't have any either
			val preprocessingValues = runResult.clusterJobs[1].pypValues(econfig)
			println("preprocessing args: $preprocessingValues")
			preprocessingValues[argInt] shouldBe null
			preprocessingValues[argStr] shouldBe null
		}
	}

	test("doesn't forward explicit default values") {
		website.createProjectAndListen { project, ws ->

			// make a raw data block, with explicit default values
			val rawDataArgs = SingleParticleRawDataArgs(econfig.argsToml {
				this[argInt] = argInt.defaultOrThrow.value
			})
			val rawDataJob = website.importBlock(project, ISingleParticleRawDataService::import, rawDataArgs)

			// make a downstream preprocessing block, with no args
			val preprocessingArgs = SingleParticlePurePreprocessingArgs("")
			val preprocessingJob = website.addBlock(project, rawDataJob, ISingleParticlePurePreprocessingService::addNode, preprocessingArgs)

			// run the project
			val runResult = website.runProject(project, listOf(rawDataJob, preprocessingJob), ws)
			runResult.clusterJobs.size shouldBe 2

			// the raw data block shouldn't have any values
			val rawDataValues = runResult.clusterJobs[0].pypValues(econfig)
			println("raw data args: $rawDataValues")
			rawDataValues[argInt] shouldBe null
			rawDataValues[argStr] shouldBe null

			// the preprocessing block shouldn't have any either
			val preprocessingValues = runResult.clusterJobs[1].pypValues(econfig)
			println("preprocessing args: $preprocessingValues")
			preprocessingValues[argInt] shouldBe null
			preprocessingValues[argStr] shouldBe null
		}
	}
})
