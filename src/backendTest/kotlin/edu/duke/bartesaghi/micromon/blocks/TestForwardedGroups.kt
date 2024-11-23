package edu.duke.bartesaghi.micromon.blocks

import edu.duke.bartesaghi.micromon.*
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

	fun ArgGroup.testArg(argId: String, type: ArgType, default: ArgValue? = null): Arg =
		Arg(groupId, argId, "Test Arg", "This is a test", type = type, default = default)

	fun Block.addGroup(group: ArgGroup): Block =
		copy(
			groupIds = groupIds + listOf(group.groupId)
		)

	// define some synthetic args for testing
	val upstreamGroup = ArgGroup("upstream", "", "")
	val argInt = upstreamGroup.testArg("argInt", ArgType.TInt(), default = ArgValue.VInt(1))
	val argStr = upstreamGroup.testArg("argStr", ArgType.TStr())
	val downstreamGroup = ArgGroup("downstream", "", "")
	val argIntD = downstreamGroup.testArg("argInt", ArgType.TInt(), default = ArgValue.VInt(5))
	val argStrD = downstreamGroup.testArg("argStr", ArgType.TStr(), default = ArgValue.VStr("bar"))

	// start one website for all of these tests
	val econfig = autoClose(EphemeralConfig {
		pypArgs = Args(
			blocks = pypArgs.blocks.map { block ->
				when (block.blockId) {

					// add the test groups to the blocks
					SingleParticleRawDataNodeConfig.configId -> block.addGroup(upstreamGroup)
					SingleParticlePurePreprocessingNodeConfig.configId -> block.addGroup(downstreamGroup)

					else -> block
				}
			},
			groups = pypArgs.groups + listOf(upstreamGroup, downstreamGroup),
			args = pypArgs.args + listOf(argInt, argStr, argIntD, argStrD)
		)
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

			// the raw data CLI should have the arg values
			val rawDataValues = runResult.clusterJobs[0].pypValues(econfig)
			println("raw data args: $rawDataValues")
			rawDataValues[argInt] shouldBe 5
			rawDataValues[argStr] shouldBe "foo"

			// the preprocessing CLI should have them too
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

			// the raw data CLI shouldn't have any values
			val rawDataValues = runResult.clusterJobs[0].pypValues(econfig)
			println("raw data args: $rawDataValues")
			rawDataValues[argInt] shouldBe null
			rawDataValues[argStr] shouldBe null

			// the preprocessing CLI shouldn't have any either
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

			// the raw data CLI shouldn't have any values
			val rawDataValues = runResult.clusterJobs[0].pypValues(econfig)
			println("raw data args: $rawDataValues")
			rawDataValues[argInt] shouldBe null
			rawDataValues[argStr] shouldBe null

			// the preprocessing CLI shouldn't have any either
			val preprocessingValues = runResult.clusterJobs[1].pypValues(econfig)
			println("preprocessing args: $preprocessingValues")
			preprocessingValues[argInt] shouldBe null
			preprocessingValues[argStr] shouldBe null
		}
	}
})
