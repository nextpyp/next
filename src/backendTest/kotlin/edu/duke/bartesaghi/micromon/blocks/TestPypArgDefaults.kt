package edu.duke.bartesaghi.micromon.blocks

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.CommandsScript
import edu.duke.bartesaghi.micromon.cluster.Container
import edu.duke.bartesaghi.micromon.linux.Posix
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe


@EnabledIf(RuntimeEnvironment.Website::class)
class TestPypArgDefaults : FunSpec({

	// start one database for all of these tests
	val database = autoClose(EphemeralMongo.start())
	autoClose(database.install())

	// define some synthetic args with default values to use for the test
	fun testArg(argId: String, type: ArgType, default: ArgValue? = null): Arg =
		Arg("micromon", argId, "Test Arg", "This is a test", type = type, default = default)
	val argBool = testArg("argBool", ArgType.TBool(), default = ArgValue.VBool(false))
	val argInt = testArg("argInt", ArgType.TInt(), default = ArgValue.VInt(1))
	val argStr = testArg("argStr", ArgType.TStr())

	// start one website for all of these tests
	val econfig = autoClose(EphemeralConfig {
		pypArgs = pypArgs.appendAll(listOf(argBool, argInt, argStr))
	})
	autoClose(econfig.install())
	val website = autoClose(EphemeralWebsite())


	test("sends nothing for default values") {
		website.createProject { project, ws ->

			// make a block, with no args
			val args = SingleParticleRawDataArgs(econfig.argsToml {
				// don't set anything for arg1 (implicit default)
				// explicity set arg2 to the default value
				this[argInt] = argInt.defaultOrThrow.value
			})
			val job = website.services.rpc(ISingleParticleRawDataService::import, website.getUserId(), project.projectId, args)

			// run the project
			val clusterJobs = website.runProject(project, listOf(job), ws)
			clusterJobs.size shouldBe 1

			// the CLI shouldn't have any of the arg values
			val sentValues = clusterJobs[0].pypValues(econfig)
			println("CLI args: $sentValues")
			sentValues[argBool] shouldBe null
			sentValues[argInt] shouldBe null
		}
	}

	test("sends something for non-default values") {
		website.createProject { project, ws ->

			// make a block, with some non-default args
			val args = SingleParticleRawDataArgs(econfig.argsToml {
				this[argBool] = true
				this[argInt] = 5
			})
			val job = website.services.rpc(ISingleParticleRawDataService::import, website.getUserId(), project.projectId, args)

			// run the project
			val clusterJobs = website.runProject(project, listOf(job), ws)
			clusterJobs.size shouldBe 1

			// the CLI shouldn't have the arg values
			val sentValues = clusterJobs[0].pypValues(econfig)
			println("CLI args: $sentValues")
			sentValues[argBool] shouldBe true
			sentValues[argInt] shouldBe 5
		}
	}

	test("resends nothing for unchanged values") {
		website.createProject { project, ws ->

			// make a block, with some non-default args
			val args = SingleParticleRawDataArgs(econfig.argsToml {
				this[argBool] = true
				this[argInt] = 5
			})
			val job = website.services.rpc(ISingleParticleRawDataService::import, website.getUserId(), project.projectId, args)

			// run the project
			website.runProject(project, listOf(job), ws)

			// run the project again
			val clusterJobs = website.runProject(project, listOf(job), ws)
			clusterJobs.size shouldBe 1

			// the CLI shouldn't have any of the arg values
			val sentValues = clusterJobs[0].pypValues(econfig)
			println("CLI args: $sentValues")
			sentValues[argBool] shouldBe null
			sentValues[argInt] shouldBe null
		}
	}

	test("resends something for changed values") {
		website.createProject { project, ws ->

			// make a block, with some non-default args
			var args = SingleParticleRawDataArgs(econfig.argsToml {
				this[argBool] = true
				this[argInt] = 5
			})
			val job = website.services.rpc(ISingleParticleRawDataService::import, website.getUserId(), project.projectId, args)

			// run the project
			website.runProject(project, listOf(job), ws)

			// change some args
			args = args.copy(values = args.values.toArgValues(econfig.pypArgs).apply {
				this[argInt] = 42
			}.toToml())
			website.services.rpc(ISingleParticleRawDataService::edit, job.jobId, args)

			// run the project again
			val clusterJobs = website.runProject(project, listOf(job), ws)
			clusterJobs.size shouldBe 1

			// the CLI should have the new arg value
			val sentValues = clusterJobs[0].pypValues(econfig)
			println("CLI args: $sentValues")
			sentValues[argBool] shouldBe null
			sentValues[argInt] shouldBe 42
		}
	}

	test("resends something for re-defaulted values") {
		website.createProject { project, ws ->

			// make a block, with some non-default args
			var args = SingleParticleRawDataArgs(econfig.argsToml {
				this[argBool] = true
				this[argInt] = 5
			})
			val job = website.services.rpc(ISingleParticleRawDataService::import, website.getUserId(), project.projectId, args)

			// run the project
			website.runProject(project, listOf(job), ws)

			// change the args back to the default values
			args = args.copy(values = args.values.toArgValues(econfig.pypArgs).apply {
				this[argBool] = false
				this[argInt] = 1
			}.toToml())
			website.services.rpc(ISingleParticleRawDataService::edit, job.jobId, args)

			// run the project again
			val clusterJobs = website.runProject(project, listOf(job), ws)
			clusterJobs.size shouldBe 1

			// the CLI should have explicit default values for the args
			val sentValues = clusterJobs[0].pypValues(econfig)
			println("CLI args: $sentValues")
			sentValues[argBool] shouldBe false
			sentValues[argInt] shouldBe 1
		}
	}

	test("resends something for removed values") {
		website.createProject { project, ws ->

			// make a block, with some non-default args
			var args = SingleParticleRawDataArgs(econfig.argsToml {
				this[argBool] = true
				this[argInt] = 5
				this[argStr] = "foo"
			})
			val job = website.services.rpc(ISingleParticleRawDataService::import, website.getUserId(), project.projectId, args)

			// run the project
			website.runProject(project, listOf(job), ws)

			// remove the args
			args = args.copy(values = args.values.toArgValues(econfig.pypArgs).apply {
				this[argBool] = null
				this[argInt] = null
				this[argStr] = null
			}.toToml())
			website.services.rpc(ISingleParticleRawDataService::edit, job.jobId, args)

			// run the project again
			val clusterJobs = website.runProject(project, listOf(job), ws)
			clusterJobs.size shouldBe 1

			// the CLI should have explicit default values for the args
			val sentValues = clusterJobs[0].pypValues(econfig)
			println("CLI args: $sentValues")
			sentValues[argBool] shouldBe false
			sentValues[argInt] shouldBe 1
			sentValues[argStr] shouldBe ""
		}
	}
})


fun ClusterJob.pypValues(econfig: EphemeralConfig): ArgValues {

	// get the pyp command args
	val cmd = (commands as CommandsScript).commands[0]
	println("CLI: $cmd")
	val tokens = Posix.tokenize(cmd)
		.dropWhile { it != Container.MockPyp.exec }
		.drop(1) // drop the exec path we just matched above
		.drop(1) // drop one more for the pyp command name, eg `gyp`

	return ArgValues.fromPypCLI(tokens, econfig.pypArgs)
}
