package edu.duke.bartesaghi.micromon.blocks

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.CommandsScript
import edu.duke.bartesaghi.micromon.cluster.Container
import edu.duke.bartesaghi.micromon.linux.Posix
import edu.duke.bartesaghi.micromon.pyp.ArgValues
import edu.duke.bartesaghi.micromon.pyp.fromPypCLI
import edu.duke.bartesaghi.micromon.services.*
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe


@EnabledIf(RuntimeEnvironment.Website::class)
class TestPypArgs : DescribeSpec({

	describe("pyp args") {

		// start one database for all of these tests
		val database = autoClose(EphemeralMongo.start())
		autoClose(database.install())

		// start one website for all of these tests
		val config = autoClose(EphemeralConfig())
		autoClose(config.install())
		val website = autoClose(EphemeralWebsite())


		// pick pyp args in the spa-rawdata block that have default values
		val dataImport = Backend.pypArgs.argOrThrow("data_import")
		val dataBin = Backend.pypArgs.argOrThrow("data_bin")


		fun ClusterJob.pypValues(): ArgValues {

			// get the pyp command args
			val cmd = (commands as CommandsScript).commands[0]
			val tokens = Posix.tokenize(cmd)
				.dropWhile { it != Container.MockPyp.exec }
				.drop(1) // drop the exec path we just matched above
				.drop(1) // drop one more for the pyp command name, eg `gyp`

			return ArgValues.fromPypCLI(tokens, Backend.pypArgs)
		}


		it("sends nothing for default values") {
			website.createProject { project, ws ->

				// make a block, with no args
				val args = SingleParticleRawDataArgs(ArgValues(Backend.pypArgs)
					.apply {
						// don't set anything for data import (implicit default)
						// explicity set dataBin to the default value
						this[dataBin] = dataBin.defaultOrThrow.value
					}
					.toToml())
				val job = website.services.rpc(ISingleParticleRawDataService::import, website.getUserId(), project.projectId, args)

				// run the project
				val clusterJobs = website.runProject(project, listOf(job), ws)
				clusterJobs.size shouldBe 1

				// the CLI shouldn't have any of the arg values
				val sentValues = clusterJobs[0].pypValues()
				sentValues[dataImport] shouldBe null
				sentValues[dataBin] shouldBe null
			}
		}

		/* TODO
		it("sends something for non-default values") {
		}
		*/

		/* TODO
		it("resends nothing for unchanged values") {
		}
		*/

		/* TODO
		it("resends something for changed values") {
		}
		*/

		/* TODO
		it("resends something for re-defaulted values") {
		}
		*/

		// TODO: test forwarded tabs too
	}
})
