package edu.duke.bartesaghi.micromon.blocks

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.pyp.*
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
			this[TomographyPickingMockArgs.numParticles] = 4
		}, null, null)
		val pickingJob = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs)

		// copy the picking block
		val copyArgs = TomographyPickingCopyArgs(pickingJob.jobId, false, false)
		val pickingJobCopy = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs, copyArgs)

		// the copied args should show up in the next position
		pickingJobCopy.args.next shouldBe pickingJob.args.next
		pickingJobCopy.args.finished shouldBe null
	}

	test("before run, changed args") {
		website.createProjectAndListen { project, ws ->

			// make a block sequence up to particle picking
			val rawDataArgs = TomographyRawDataArgs("")
			val rawDataJob = website.importBlock(project, ITomographyRawDataService::import, rawDataArgs)
			val preprocessingArgs = TomographyPurePreprocessingArgs("")
			val preprocessingJob = website.addBlock(project, rawDataJob, ITomographyPurePreprocessingService::addNode, preprocessingArgs)
			val pickingArgs = TomographyPickingArgs(econfig.argsToml {
				this[TomographyPickingMockArgs.numParticles] = 4
			}, null, null)
			val pickingJob = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs)

			// copy the picking block, but change the args
			val pickingArgsCopy = TomographyPickingArgs(econfig.argsToml {
				this[TomographyPickingMockArgs.numParticles] = 6
			}, null, null)
			val copyArgs = TomographyPickingCopyArgs(pickingJob.jobId, false, false)
			val pickingJobCopy = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgsCopy, copyArgs)

			// the args should show up in the next position
			pickingJobCopy.args.next shouldBe pickingArgsCopy
			pickingJobCopy.args.finished shouldBe null
		}
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
				this[TomographyPickingMockArgs.numTiltSeries] = 2
				this[TomographyPickingMockArgs.numParticles] = 4
			}, null, null)
			var pickingJob = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs)

			// run the project
			val runResult = website.runProject(project, listOf(rawDataJob, preprocessingJob, pickingJob), ws)
			pickingJob = runResult.getJobData(pickingJob)

			// copy the picking block
			val copyArgs = TomographyPickingCopyArgs(pickingJob.jobId, false, false)
			val pickingJobCopy = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs, copyArgs)

			// the copied args should show up in the next position
			pickingJobCopy.args.next shouldBe pickingJob.args.finished
			pickingJobCopy.args.finished shouldBe null
		}
	}

	test("after run, copy data") {
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
				this[econfig.pypArgs.tomoPickMethod] = TomoPickMethod.Auto.id
				this[TomographyPickingMockArgs.numTiltSeries] = 2
				this[TomographyPickingMockArgs.numParticles] = 4
			}, null, null)
			var pickingJob = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs)

			// run the project
			val runResult = website.runProject(project, listOf(rawDataJob, preprocessingJob, pickingJob), ws)
			pickingJob = runResult.getJobData(pickingJob)

			// copy the picking block
			val copyArgs = TomographyPickingCopyArgs(pickingJob.jobId, true, false)
			val pickingJobCopy = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs, copyArgs)

			// the copied args should show up in the finished position
			pickingJobCopy.args.next shouldBe null
			pickingJobCopy.args.finished shouldBe pickingJob.args.finished

			// the tilt series should have been copied
			val srcTiltSeriesIds = TiltSeries.getAll(pickingJob.jobId) { it.toList() }
				.map { it.tiltSeriesId }
				.sorted()
			srcTiltSeriesIds.size shouldBe 2
			val dstTiltSeriesIds = TiltSeries.getAll(pickingJobCopy.jobId) { it.toList() }
				.map { it.tiltSeriesId }
				.sorted()
			dstTiltSeriesIds shouldBe srcTiltSeriesIds

			// the particles should have been copied too
			val srcLists = website.services.rpc(IParticlesService::getLists, OwnerType.Project, pickingJob.jobId)
			srcLists shouldBe listOf(ParticlesList.autoParticles3D(pickingJob.jobId))
			val dstLists = website.services.rpc(IParticlesService::getLists, OwnerType.Project, pickingJobCopy.jobId)
			dstLists shouldBe listOf(ParticlesList.autoParticles3D(pickingJobCopy.jobId))
			for (tiltSeriesId in dstTiltSeriesIds) {
				val srcParticles = website.getParticles3d(OwnerType.Project, srcLists[0], tiltSeriesId)
					.toMap()
				srcParticles.size shouldBe 4
				val dstParticles = website.getParticles3d(OwnerType.Project, dstLists[0], tiltSeriesId)
					.toMap()
				dstParticles shouldBe srcParticles
			}
		}
	}

	test("after run, copy data and particles") {
		website.createProjectAndListen { project, ws ->

			// make a block sequence up to particle picking, and actually pick some particles
			val rawDataArgs = TomographyRawDataArgs("")
			val rawDataJob = website.importBlock(project, ITomographyRawDataService::import, rawDataArgs)
			val preprocessingArgs = TomographyPurePreprocessingArgs(econfig.argsToml {
				this[TomographyPurePreprocessingMockArgs.numTiltSeries] = 2
				this[TomographyPurePreprocessingMockArgs.numTilts] = 2
			})
			val preprocessingJob = website.addBlock(project, rawDataJob, ITomographyPurePreprocessingService::addNode, preprocessingArgs)
			val pickingArgs = TomographyPickingArgs(econfig.argsToml {
				this[econfig.pypArgs.tomoPickMethod] = TomoPickMethod.Auto.id
				this[TomographyPickingMockArgs.numTiltSeries] = 2
				this[TomographyPickingMockArgs.numParticles] = 4
			}, null, null)
			var pickingJob = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs)

			// run the project
			val runResult = website.runProject(project, listOf(rawDataJob, preprocessingJob, pickingJob), ws)
			pickingJob = runResult.getJobData(pickingJob)

			// copy the picking block
			val copyArgs = TomographyPickingCopyArgs(pickingJob.jobId, true, true)
			val pickingJobCopy = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs, copyArgs)

			// only the finished args should have been copied
			pickingJobCopy.args.next shouldBe null
			pickingJobCopy.args.finished shouldBe pickingJob.args.finished

			// the particles should have been copied into a manual list
			val srcLists = website.services.rpc(IParticlesService::getLists, OwnerType.Project, pickingJob.jobId)
			srcLists shouldBe listOf(ParticlesList.autoParticles3D(pickingJob.jobId))
			val dstLists = website.services.rpc(IParticlesService::getLists, OwnerType.Project, pickingJobCopy.jobId)
			dstLists shouldBe listOf(ParticlesList.manualParticles3D(pickingJobCopy.jobId))
			for (tiltSeries in TiltSeries.getAll(pickingJobCopy.jobId) { it.toList() }) {
				val srcParticles = website.getParticles3d(OwnerType.Project, srcLists[0], tiltSeries.tiltSeriesId)
					.toMap()
				srcParticles.size shouldBe 4
				val dstParticles = website.getParticles3d(OwnerType.Project, dstLists[0], tiltSeries.tiltSeriesId)
					.toMap()
				dstParticles shouldBe srcParticles
			}
		}
	}

	test("after run, copy data and virions") {
		website.createProjectAndListen { project, ws ->

			// make a block sequence up to particle picking, and actually pick some particles
			val rawDataArgs = TomographyRawDataArgs("")
			val rawDataJob = website.importBlock(project, ITomographyRawDataService::import, rawDataArgs)
			val preprocessingArgs = TomographyPurePreprocessingArgs(econfig.argsToml {
				this[TomographyPurePreprocessingMockArgs.numTiltSeries] = 2
				this[TomographyPurePreprocessingMockArgs.numTilts] = 2
			})
			val preprocessingJob = website.addBlock(project, rawDataJob, ITomographyPurePreprocessingService::addNode, preprocessingArgs)
			val pickingArgs = TomographyPickingArgs(econfig.argsToml {
				this[econfig.pypArgs.tomoPickMethod] = TomoPickMethod.Virions.id
				this[TomographyPickingMockArgs.numTiltSeries] = 2
				this[TomographyPickingMockArgs.numParticles] = 4
			}, null, null)
			var pickingJob = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs)

			// run the project
			val runResult = website.runProject(project, listOf(rawDataJob, preprocessingJob, pickingJob), ws)
			pickingJob = runResult.getJobData(pickingJob)

			// copy the picking block
			val copyArgs = TomographyPickingCopyArgs(pickingJob.jobId, true, true)
			val pickingJobCopy = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs, copyArgs)

			// only the finished args should have been copied
			pickingJobCopy.args.next shouldBe null
			pickingJobCopy.args.finished shouldBe pickingJob.args.finished

			// the virions should have been copied into a manual list
			val srcLists = website.services.rpc(IParticlesService::getLists, OwnerType.Project, pickingJob.jobId)
			srcLists shouldBe listOf(ParticlesList.autoVirions(pickingJob.jobId))
			val dstLists = website.services.rpc(IParticlesService::getLists, OwnerType.Project, pickingJobCopy.jobId)
			dstLists shouldBe listOf(ParticlesList.manualParticles3D(pickingJobCopy.jobId))
			for (tiltSeries in TiltSeries.getAll(pickingJobCopy.jobId) { it.toList() }) {
				val srcParticles = website.getParticles3d(OwnerType.Project, srcLists[0], tiltSeries.tiltSeriesId)
					.toMap()
				srcParticles.size shouldBe 4
				val dstParticles = website.getParticles3d(OwnerType.Project, dstLists[0], tiltSeries.tiltSeriesId)
					.toMap()
				dstParticles shouldBe srcParticles
			}
		}
	}
})
