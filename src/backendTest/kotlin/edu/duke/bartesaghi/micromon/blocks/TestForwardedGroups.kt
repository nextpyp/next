package edu.duke.bartesaghi.micromon.blocks

import edu.duke.bartesaghi.micromon.EphemeralConfig
import edu.duke.bartesaghi.micromon.EphemeralMongo
import edu.duke.bartesaghi.micromon.EphemeralWebsite
import edu.duke.bartesaghi.micromon.nodes.SingleParticlePurePreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.nodes.SingleParticleRawDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe


class TestForwardedGroups : FunSpec({

	// start one database for all of these tests
	val database = autoClose(EphemeralMongo.start())
	autoClose(database.install())

	// define some synthetic args in forwarded groups
	val testGroup = ArgGroup("forwarded", "", "")
	fun testArg(argId: String, type: ArgType, default: ArgValue? = null): Arg =
		Arg(testGroup.groupId, argId, "Test Arg", "This is a test", type = type, default = default)
	val argInt = testArg("argInt", ArgType.TInt(), default = ArgValue.VInt(1))
	val argStr = testArg("argStr", ArgType.TStr())

	// start one website for all of these tests
	val econfig = autoClose(EphemeralConfig {
		pypArgs = Args(
			blocks = pypArgs.blocks.map { block ->
				when (block.blockId) {

					// add the test group to the raw data block as a regluar group
					SingleParticleRawDataNodeConfig.configId -> block.copy(
						groupIds = block.groupIds + listOf(testGroup.groupId)
					)

					// add the test group to the preprocessing block as a forwarded group
					SingleParticlePurePreprocessingNodeConfig.configId -> block.copy(
						forwardedGroupIds = block.forwardedGroupIds + listOf(testGroup.groupId)
					)

					else -> block
				}
			},
			groups = pypArgs.groups + listOf(testGroup),
			args = pypArgs.args + listOf(argInt, argStr)
		)
	})
	autoClose(econfig.install())
	val website = autoClose(EphemeralWebsite())


	test("forwards groups") {
		website.createProject { project, ws ->

			// make a raw data block, with some arg values
			val rawDataArgs = SingleParticleRawDataArgs(econfig.argsToml {
				this[argInt] = 5
				this[argStr] = "foo"
			})
			val rawDataJob = website.services.rpc(ISingleParticleRawDataService::import, website.getUserId(), project.projectId, rawDataArgs)

			// make a downstream preprocessing block, with no args
			val link = CommonJobData.DataId(rawDataJob.jobId, SingleParticleRawDataNodeConfig.movies.id)
			val preprocessingArgs = SingleParticlePurePreprocessingArgs("")
			val preprocessingJob = website.services.rpc(ISingleParticlePurePreprocessingService::addNode, website.getUserId(), project.projectId, link, preprocessingArgs)

			// run the project
			val clusterJobs = website.runProject(project, listOf(rawDataJob, preprocessingJob), ws)
			clusterJobs.size shouldBe 2

			// the raw data CLI should have the arg values
			val rawDataValues = clusterJobs[0].pypValues(econfig)
			println("raw data CLI args: $rawDataValues")
			rawDataValues[argInt] shouldBe 5
			rawDataValues[argStr] shouldBe "foo"

			// the preprocessing CLI should have them too
			val preprocessingValues = clusterJobs[1].pypValues(econfig)
			println("preprocessing CLI args: $preprocessingValues")
			preprocessingValues[argInt] shouldBe 5
			preprocessingValues[argStr] shouldBe "foo"
		}
	}

	test("doesn't forward implicit default values") {
		website.createProject { project, ws ->

			// make a raw data block, with some arg values
			val rawDataArgs = SingleParticleRawDataArgs("")
			val rawDataJob = website.services.rpc(ISingleParticleRawDataService::import, website.getUserId(), project.projectId, rawDataArgs)

			// make a downstream preprocessing block, with no args
			val link = CommonJobData.DataId(rawDataJob.jobId, SingleParticleRawDataNodeConfig.movies.id)
			val preprocessingArgs = SingleParticlePurePreprocessingArgs("")
			val preprocessingJob = website.services.rpc(ISingleParticlePurePreprocessingService::addNode, website.getUserId(), project.projectId, link, preprocessingArgs)

			// run the project
			val clusterJobs = website.runProject(project, listOf(rawDataJob, preprocessingJob), ws)
			clusterJobs.size shouldBe 2

			// the raw data CLI shouldn't have any values
			val rawDataValues = clusterJobs[0].pypValues(econfig)
			println("raw data CLI args: $rawDataValues")
			rawDataValues[argInt] shouldBe null
			rawDataValues[argStr] shouldBe null

			// the preprocessing CLI shouldn't have any either
			val preprocessingValues = clusterJobs[1].pypValues(econfig)
			println("preprocessing CLI args: $preprocessingValues")
			preprocessingValues[argInt] shouldBe null
			preprocessingValues[argStr] shouldBe null
		}
	}

	test("doesn't forward explicit default values") {
		website.createProject { project, ws ->

			// make a raw data block, with explicit default values
			val rawDataArgs = SingleParticleRawDataArgs(econfig.argsToml {
				this[argInt] = argInt.defaultOrThrow.value
			})
			val rawDataJob = website.services.rpc(ISingleParticleRawDataService::import, website.getUserId(), project.projectId, rawDataArgs)

			// make a downstream preprocessing block, with no args
			val link = CommonJobData.DataId(rawDataJob.jobId, SingleParticleRawDataNodeConfig.movies.id)
			val preprocessingArgs = SingleParticlePurePreprocessingArgs("")
			val preprocessingJob = website.services.rpc(ISingleParticlePurePreprocessingService::addNode, website.getUserId(), project.projectId, link, preprocessingArgs)

			// run the project
			val clusterJobs = website.runProject(project, listOf(rawDataJob, preprocessingJob), ws)
			clusterJobs.size shouldBe 2

			// the raw data CLI shouldn't have any values
			val rawDataValues = clusterJobs[0].pypValues(econfig)
			println("raw data CLI args: $rawDataValues")
			rawDataValues[argInt] shouldBe null
			rawDataValues[argStr] shouldBe null

			// the preprocessing CLI shouldn't have any either
			val preprocessingValues = clusterJobs[1].pypValues(econfig)
			println("preprocessing CLI args: $preprocessingValues")
			preprocessingValues[argInt] shouldBe null
			preprocessingValues[argStr] shouldBe null
		}
	}
})
