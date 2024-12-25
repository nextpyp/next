package edu.duke.bartesaghi.micromon.blocks

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.slurm.Template
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe


@EnabledIf(RuntimeEnvironment.Website::class)
class TestMicromonArgs : FunSpec({

	// start one database for all of these tests
	val database = autoClose(EphemeralMongo.start())
	autoClose(database.install())

	// start one website for all of these tests
	val econfig = autoClose(EphemeralConfig())
	autoClose(econfig.install())
	val website = autoClose(EphemeralWebsite())


	test("doesn't send micromon args to pyp") {
		website.createProjectAndListen { project, ws ->

			// make a raw data block, with some micromon arg values
			val rawDataArgs = SingleParticleRawDataArgs(econfig.argsToml {
				this[MicromonArgs.slurmLaunchCpus] = 2
				this[MicromonArgs.slurmTemplate] = Template.Key.DEFAULT
				// NOTE: we have to pick a real template here, or the job won't launch
			})
			val rawDataJob = website.importBlock(project, ISingleParticleRawDataService::import, rawDataArgs)

			// run the project
			val runResult = website.runProject(project, listOf(rawDataJob), ws)
			runResult.clusterJobs.size shouldBe 1

			// pyp shouldn't get any of the micromon args
			val valuesForPyp = runResult.clusterJobs[0].pypValues(econfig)
			println("values for pyp: $valuesForPyp")
			valuesForPyp[MicromonArgs.slurmLaunchCpus] shouldBe null
			valuesForPyp[MicromonArgs.slurmTemplate] shouldBe null

			// but the website should get them all
			val valuesForWebsite = rawDataJob.job().launchArgValues()
			println("values for website: $valuesForPyp")
			valuesForWebsite[MicromonArgs.slurmLaunchCpus] shouldBe 2
			valuesForWebsite[MicromonArgs.slurmTemplate] shouldBe Template.Key.DEFAULT
		}
	}
})
