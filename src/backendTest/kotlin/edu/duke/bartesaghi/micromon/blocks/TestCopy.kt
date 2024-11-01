package edu.duke.bartesaghi.micromon.blocks

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.pyp.TomographyPickingMockArgs
import edu.duke.bartesaghi.micromon.pyp.TomographyPurePreprocessingMockArgs
import edu.duke.bartesaghi.micromon.services.*
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe


@EnabledIf(RuntimeEnvironment.Website::class)
class TestCopy : FunSpec({

	// start one database for all of these tests
	val database = autoClose(EphemeralMongo.start())
	autoClose(database.install())

	// start one website for all of these tests
	val econfig = autoClose(EphemeralConfig())
	autoClose(econfig.install())
	val website = autoClose(EphemeralWebsite())


	test("before run") {

		val project = website.createProject()

		// make a block sequence up to particle picking
		val rawDataArgs = TomographyRawDataArgs("")
		val rawDataJob = website.importBlock(project, ITomographyRawDataService::import, rawDataArgs)
		val preprocessingArgs = TomographyPurePreprocessingArgs("")
		val preprocessingJob = website.addBlock(project, rawDataJob, ITomographyPurePreprocessingService::addNode, preprocessingArgs)
		val pickingArgs = TomographyPickingArgs(econfig.argsToml {
			this[TomographyPickingMockArgs.numParticles] = 5
		}, null, null)
		val pickingJob = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs)

		// copy the picking block
		val copyArgs = TomographyPickingCopyArgs(pickingJob.jobId, false, false)
		val pickingJobCopy = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs, copyArgs)

		// the copied args should show up in the next position
		pickingJobCopy.args.next shouldBe pickingJob.args.next
		pickingJobCopy.args.finished shouldBe null
	}

	test("after run") {
		website.createProjectAndListen { project, ws ->

			// make a block sequence up to particle picking
			val rawDataArgs = TomographyRawDataArgs("")
			val rawDataJob = website.importBlock(project, ITomographyRawDataService::import, rawDataArgs)
			val preprocessingArgs = TomographyPurePreprocessingArgs(econfig.argsToml {
				this[TomographyPurePreprocessingMockArgs.numTiltSeries] = 2
				this[TomographyPurePreprocessingMockArgs.numTilts] = 2
			})
			val preprocessingJob = website.addBlock(project, rawDataJob, ITomographyPurePreprocessingService::addNode, preprocessingArgs)
			val pickingArgs = TomographyPickingArgs(econfig.argsToml {
				this[TomographyPickingMockArgs.numParticles] = 4
			}, null, null)
			var pickingJob = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs)

			// run the project
			val runResult = website.runProject(project, listOf(rawDataJob, preprocessingJob, pickingJob), ws)
			pickingJob = runResult.getJobData(pickingJob)

			// copy the picking block
			val copyArgs = TomographyPickingCopyArgs(pickingJob.jobId, false, false)
			val pickingJobCopy = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs, copyArgs)

			// the copied args should show up in the finished position
			// TODO: NEXTTIME: this test fails here, fix it!
			pickingJobCopy.args.next shouldBe null
			pickingJobCopy.args.finished shouldBe pickingJob.args.finished
		}
	}

	test("after run, changed args") {
		website.createProjectAndListen { project, ws ->

			// make a block sequence up to particle picking
			val rawDataArgs = TomographyRawDataArgs("")
			val rawDataJob = website.importBlock(project, ITomographyRawDataService::import, rawDataArgs)
			val preprocessingArgs = TomographyPurePreprocessingArgs(econfig.argsToml {
				this[TomographyPurePreprocessingMockArgs.numTiltSeries] = 2
				this[TomographyPurePreprocessingMockArgs.numTilts] = 2
			})
			val preprocessingJob = website.addBlock(project, rawDataJob, ITomographyPurePreprocessingService::addNode, preprocessingArgs)
			val pickingArgs = TomographyPickingArgs(econfig.argsToml {
				this[TomographyPickingMockArgs.numParticles] = 4
			}, null, null)
			val pickingJob = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs)

			// run the project
			website.runProject(project, listOf(rawDataJob, preprocessingJob, pickingJob), ws)

			// copy the picking block, but change the args
			val pickingForCopyArgs = TomographyPickingArgs(econfig.argsToml {
				this[TomographyPickingMockArgs.numParticles] = 6
			}, null, null)
			val copyArgs = TomographyPickingCopyArgs(pickingJob.jobId, false, false)
			val pickingJobCopy = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingForCopyArgs, copyArgs)

			// the args should show up in the next position
			pickingJobCopy.args.next shouldBe pickingForCopyArgs
			pickingJobCopy.args.finished shouldBe null
		}
	}

	test("after run, with data") {
		website.createProjectAndListen { project, ws ->

			// make a block sequence up to particle picking
			val rawDataArgs = TomographyRawDataArgs("")
			val rawDataJob = website.importBlock(project, ITomographyRawDataService::import, rawDataArgs)
			val preprocessingArgs = TomographyPurePreprocessingArgs(econfig.argsToml {
				this[TomographyPurePreprocessingMockArgs.numTiltSeries] = 2
				this[TomographyPurePreprocessingMockArgs.numTilts] = 2
			})
			val preprocessingJob = website.addBlock(project, rawDataJob, ITomographyPurePreprocessingService::addNode, preprocessingArgs)
			val pickingArgs = TomographyPickingArgs(econfig.argsToml {
				this[TomographyPickingMockArgs.numParticles] = 4
			}, null, null)
			var pickingJob = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs)

			// run the project
			val runResult = website.runProject(project, listOf(rawDataJob, preprocessingJob, pickingJob), ws)
			pickingJob = runResult.getJobData(pickingJob)

			// copy the picking block
			val copyArgs = TomographyPickingCopyArgs(pickingJob.jobId, true, false)
			val pickingJobCopy = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs, copyArgs)

			// only the finished args should have been copied
			pickingJobCopy.args.next shouldBe null
			pickingJobCopy.args.finished shouldBe pickingJob.args.finished

			// the particles should have been copied too
			val srcLists = website.services.rpc(IParticlesService::getLists, OwnerType.Project, pickingJob.jobId)
			val dstLists = website.services.rpc(IParticlesService::getLists, OwnerType.Project, pickingJobCopy.jobId)
			dstLists shouldBe srcLists
			srcLists.size shouldBe 1
			for (name in srcLists.map { it.name }) {
				val srcCount = website.services.rpc(IParticlesService::countParticles, OwnerType.Project, pickingJob.jobId, name, null)
				val dstCount = website.services.rpc(IParticlesService::countParticles, OwnerType.Project, pickingJobCopy.jobId, name, null)
				dstCount shouldBe srcCount
				srcCount shouldBe 8
			}
		}
	}
})
