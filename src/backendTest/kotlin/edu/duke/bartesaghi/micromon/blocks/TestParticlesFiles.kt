package edu.duke.bartesaghi.micromon.blocks

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.div


@EnabledIf(RuntimeEnvironment.Website::class)
class TestParticlesFiles : FunSpec({

	// start one database for all of these tests
	val database = autoClose(EphemeralMongo.start())
	autoClose(database.install())

	// start one website for all of these tests
	val econfig = autoClose(EphemeralConfig())
	autoClose(econfig.install())
	val website = autoClose(EphemeralWebsite())

	test("tomography write manual particles") {
		website.createProjectAndListen { project, ws ->

			// make a whole particle picking pipeline, using manual particles
			val rawDataArgs = TomographyRawDataArgs("")
			val rawDataJob = website.importBlock(project, ITomographyRawDataService::import, rawDataArgs)
			val preprocessingArgs = TomographyPurePreprocessingArgs("")
			val preprocessingJob = website.addBlock(project, rawDataJob, ITomographyPurePreprocessingService::addNode, preprocessingArgs)
			val pickingArgs = TomographyPickingArgs(econfig.argsToml {
				this[econfig.pypArgs.tomoPickMethod] = TomoPickMethod.Manual.id
				this[TomographyPickingMockArgs.numTiltSeries] = 1
			}, null, null)
			val pickingJob = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, pickingArgs)

			// run the project
			website.runProject(project, listOf(rawDataJob, preprocessingJob, pickingJob), ws)

			// get the tilt series
			val tiltSerieses = TiltSeries.getAll(pickingJob.jobId) { it.toList() }
			tiltSerieses.size shouldBe 1
			val tiltSeries = tiltSerieses[0]

			// pick some manual particles
			val list = ParticlesList.manualParticles3D(pickingJob.jobId)
			website.services.rpc(IParticlesService::addList, OwnerType.Project, list.ownerId, list.name, list.type)
			val particles = listOf(
				Particle3D.fromUnbinned(5, 6, 7, 4.2),
				Particle3D.fromUnbinned(3, 2, 1, 3.14)
			)
			for (particle in particles) {
				website.services.rpc(IParticlesService::addParticle3D, OwnerType.Project, list.ownerId, list.name, tiltSeries.tiltSeriesId, particle)
			}

			// make a block that uses the manual particles
			val refinementArgs = TomographyCoarseRefinementArgs("", null)
			val refinementJob = website.addBlock(project, pickingJob, ITomographyCoarseRefinementService::addNode, refinementArgs)

			// run the refinement block
			website.runProject(project, listOf(refinementJob), ws)
			val dir = refinementJob.job().dir

			// look for the particles file
			val particlesPath = dir / "next" / "${tiltSeries.tiltSeriesId}.next"
			particlesPath.exists() shouldBe true
			particlesPath.readString().trim() shouldBe """
				|5	6	7	4.2
				|3	2	1	3.14
			""".trimMargin()
		}
	}

	test("tomography write manual virion thresholds") {
		website.createProjectAndListen { project, ws ->

			// make a whole particle picking pipeline, using manual virions
			val rawDataArgs = TomographyRawDataArgs("")
			val rawDataJob = website.importBlock(project, ITomographyRawDataService::import, rawDataArgs)
			val preprocessingArgs = TomographyPurePreprocessingArgs("")
			val preprocessingJob = website.addBlock(project, rawDataJob, ITomographyPurePreprocessingService::addNode, preprocessingArgs)
			val virionPickingArgs = TomographyPickingArgs(econfig.argsToml {
				this[econfig.pypArgs.tomoPickMethod] = TomoPickMethod.Manual.id
				this[TomographyPickingMockArgs.numTiltSeries] = 1
			}, null, null)
			val virionPickingJob = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, virionPickingArgs)

			// run the project
			website.runProject(project, listOf(rawDataJob, preprocessingJob, virionPickingJob), ws)

			// get the tilt series
			val tiltSerieses = TiltSeries.getAll(virionPickingJob.jobId) { it.toList() }
			tiltSerieses.size shouldBe 1
			val tiltSeries = tiltSerieses[0]

			// pick some manual particles
			val pickingList = ParticlesList.manualParticles3D(virionPickingJob.jobId)
			website.services.rpc(IParticlesService::addList, OwnerType.Project, pickingList.ownerId, pickingList.name, pickingList.type)
			val virions = listOf(
				Particle3D.fromUnbinned(5, 6, 7, 4.2),
				Particle3D.fromUnbinned(3, 2, 1, 3.14)
			)
			for (virion in virions) {
				website.services.rpc(IParticlesService::addParticle3D, OwnerType.Project, pickingList.ownerId, pickingList.name, tiltSeries.tiltSeriesId, virion)
			}

			// make a segmentation block that uses the virions
			val segmentationArgs = TomographySegmentationClosedArgs("", null)
			val segmentationJob = website.addBlock(project, virionPickingJob, ITomographySegmentationClosedService::addNode, segmentationArgs)

			// run the segmentation block
			website.runProject(project, listOf(segmentationJob), ws)

			// check the virions from the segmentation block
			val segmentationList = ParticlesList.autoVirions(segmentationJob.jobId)
			website.getParticles3d(OwnerType.Project, segmentationList, tiltSeries.tiltSeriesId)
				.toMap() shouldBe mapOf(
					1 to virions[0],
					2 to virions[1]
				)

			// pick thresholds for the segmentation
			val thresholds = mapOf(
				1 to 5,
				2 to 7
			)
			for ((virioni, threshold) in thresholds) {
				website.services.rpc(IParticlesService::setVirionThreshold, OwnerType.Project, segmentationList.ownerId, segmentationList.name, tiltSeries.tiltSeriesId, virioni, threshold)
			}
			website.services.rpc(IParticlesService::getVirionThresholds, OwnerType.Project, segmentationList.ownerId, segmentationList.name, tiltSeries.tiltSeriesId)
				.thresholdsByParticleId shouldBe thresholds

			// add a spike picking block to use the thresholds
			val spikePickingArgs = TomographyPickingClosedArgs("")
			val spikePickingJob = website.addBlock(project, segmentationJob, ITomographyPickingClosedService::addNode, spikePickingArgs)

			// run the spike picking job
			website.runProject(project, listOf(spikePickingJob), ws)

			val dir = spikePickingJob.job().dir

			// look for the particles file
			val particlesPath = dir / "next" / "${tiltSeries.tiltSeriesId}.next"
			particlesPath.exists() shouldBe true
			particlesPath.readString().trim() shouldBe """
				|5	6	7	4.2
				|3	2	1	3.14
			""".trimMargin()

			// check the thresholds file
			val thresholdsPath = dir / "next" / "virion_thresholds.next"
			thresholdsPath.exists() shouldBe true
			thresholdsPath.readString().trim() shouldBe """
				|${tiltSeries.tiltSeriesId}	0	5
				|${tiltSeries.tiltSeriesId}	1	7
			""".trimMargin()
		}
	}

	test("tomography write virion thresholds, after auto virions copy and edit") {
		website.createProjectAndListen { project, ws ->

			// make a whole particle picking pipeline, using auto virions
			val rawDataArgs = TomographyRawDataArgs("")
			val rawDataJob = website.importBlock(project, ITomographyRawDataService::import, rawDataArgs)
			val preprocessingArgs = TomographyPurePreprocessingArgs("")
			val preprocessingJob = website.addBlock(project, rawDataJob, ITomographyPurePreprocessingService::addNode, preprocessingArgs)
			val virionPickingArgs = TomographyPickingArgs(econfig.argsToml {
				this[econfig.pypArgs.tomoPickMethod] = TomoPickMethod.Virions.id
				this[TomographyPickingMockArgs.numTiltSeries] = 1
				this[TomographyPickingMockArgs.numParticles] = 2
				this[TomographyPickingMockArgs.threshold] = 3
			}, null, null)
			val virionPickingJob = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, virionPickingArgs)

			// run the project
			website.runProject(project, listOf(rawDataJob, preprocessingJob, virionPickingJob), ws)

			// make sure we picked some virions
			val tiltSerieses = TiltSeries.getAll(virionPickingJob.jobId) { it.toList() }
			tiltSerieses.size shouldBe 1
			val tiltSeries = tiltSerieses[0]
			val virions = website.getParticles3d(OwnerType.Project, ParticlesList.autoVirions(virionPickingJob.jobId), tiltSeries.tiltSeriesId)
				.toMap()
			virions.size shouldBe 2

			// copy the picking block to get make the virions editable
			val copyArgs = TomographyPickingCopyArgs(virionPickingJob.jobId, true, true)
			val virionPickingArgsCopy = TomographyPickingArgs(econfig.argsToml {
				//this[econfig.pypArgs.tomoPickMethod] = TomoPickMethod.Manual.id
				this[econfig.pypArgs.tomoPickMethod] = TomoPickMethod.Virions.id
				// TODO: what about virions mode?
				this[TomographyPickingMockArgs.numTiltSeries] = 1
				this[TomographyPickingMockArgs.numParticles] = 2
				this[TomographyPickingMockArgs.threshold] = 3
			}, null, null)
			val virionPickingCopyJob = website.addBlock(project, preprocessingJob, ITomographyPickingService::addNode, virionPickingArgsCopy, copyArgs)

			// get the particle lists for the newly-copied block
			// there should only be one: manual particles
			val pickingCopyLists = website.services.rpc(IParticlesService::getLists, OwnerType.Project, virionPickingCopyJob.jobId)
			pickingCopyLists shouldBe listOf(ParticlesList.manualParticles3D(virionPickingCopyJob.jobId))

			// get the tilt series
			val tiltSeriesesCopy = TiltSeries.getAll(virionPickingCopyJob.jobId) { it.toList() }
			tiltSeriesesCopy.size shouldBe 1
			val tiltSeriesCopy = tiltSeriesesCopy[0]

			// check the particles
			val virionsCopy = website.getParticles3d(OwnerType.Project, pickingCopyLists[0], tiltSeriesCopy.tiltSeriesId)
				.toMap()
				.toMutableMap()
			virionsCopy.size shouldBe 2

			// edit the virions
			for (virionId in virionsCopy.keys.toList()) {
				website.services.rpc(IParticlesService::deleteParticle, OwnerType.Project, virionPickingCopyJob.jobId, ParticlesList.ManualParticles, tiltSeries.tiltSeriesId, virionId)
			}
			suspend fun addVirion(virion: Particle3D) {
				val virionId = website.services.rpc(IParticlesService::addParticle3D, OwnerType.Project, virionPickingCopyJob.jobId, ParticlesList.ManualParticles, tiltSeries.tiltSeriesId, virion)
				virionsCopy[virionId] = virion
			}
			addVirion(Particle3D.fromUnbinned(5, 6, 7, 4.2))
			addVirion(Particle3D.fromUnbinned(3, 2, 1, 3.14))

			// run segmentation on the virions
			val segmentationArgs = TomographySegmentationClosedArgs(econfig.argsToml {
				this[TomographySegmentationClosedMockArgs.numTiltSeries] = 1
				this[TomographySegmentationClosedMockArgs.threshold] = 3
			}, null)
			val segmentationJob = website.addBlock(project, virionPickingCopyJob, ITomographySegmentationClosedService::addNode, segmentationArgs)
			website.runProject(project, listOf(virionPickingCopyJob, segmentationJob), ws)

			// check the default thresholds
			val thresholds = mutableMapOf(
				1 to 3,
				2 to 3
			)
			website.services.rpc(IParticlesService::getVirionThresholds, OwnerType.Project, segmentationJob.jobId, ParticlesList.AutoVirions, tiltSeries.tiltSeriesId)
				.thresholdsByParticleId shouldBe thresholds

			// edit the thresholds
			thresholds[1] = 5
			thresholds[2] = 7
			for ((virioni, threshold) in thresholds) {
				website.services.rpc(IParticlesService::setVirionThreshold, OwnerType.Project, segmentationJob.jobId, ParticlesList.AutoVirions, tiltSeries.tiltSeriesId, virioni, threshold)
			}
			website.services.rpc(IParticlesService::getVirionThresholds, OwnerType.Project, segmentationJob.jobId, ParticlesList.AutoVirions, tiltSeries.tiltSeriesId)
				.thresholdsByParticleId shouldBe thresholds

			// run a spike picking block to use the thresholds
			val spikePickingArgs = TomographyPickingClosedArgs("")
			val spikePickingJob = website.addBlock(project, segmentationJob, ITomographyPickingClosedService::addNode, spikePickingArgs)
			website.runProject(project, listOf(spikePickingJob), ws)

			val dir = spikePickingJob.job().dir

			// look for the particles file
			val particlesPath = dir / "next" / "${tiltSeries.tiltSeriesId}.next"
			particlesPath.exists() shouldBe true
			particlesPath.readString().trim() shouldBe """
				|5	6	7	4.2
				|3	2	1	3.14
			""".trimMargin()

			// check the thresholds file
			val thresholdsPath = dir / "next" / "virion_thresholds.next"
			thresholdsPath.exists() shouldBe true
			thresholdsPath.readString().trim() shouldBe """
				|${tiltSeries.tiltSeriesId}	0	5
				|${tiltSeries.tiltSeriesId}	1	7
			""".trimMargin()
		}
	}
})
