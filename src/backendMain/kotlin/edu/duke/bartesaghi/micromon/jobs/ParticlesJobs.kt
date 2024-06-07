package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.createDirsIfNeededAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.writeStringAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.writerAs
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.ParticlesList
import java.nio.file.Path
import kotlin.io.path.div


object ParticlesJobs {

	suspend fun writeSingleParticle(user: User, jobId: String, dir: Path, particlesList: ParticlesList) {

		val trainDir = dir / "train"
		val nextDir = dir / "next"

		// write the name of the picked particles
		val particlesNamePath = trainDir / "current_list.txt"
		particlesNamePath.parent.createDirsIfNeededAs(user.osUsername)
		val particlesFilename = particlesList.name
			.sanitizeFileName()
			.replace(" ", "_") // pyp can't handle spaces or periods in paths either
			.replace(".", "_")
		particlesNamePath.writeStringAs(user.osUsername, particlesFilename)

		// write out the micrographs and coordinates
		val micrographsPath = trainDir / "${particlesFilename}_images.txt"
		micrographsPath.parent.createDirsIfNeededAs(user.osUsername)
		micrographsPath.writerAs(user.osUsername).use { writerMicrographs ->
			writerMicrographs.write("image_name\tpath\n")

			val coordinatesPath = trainDir / "${particlesFilename}_coordinates.txt"
			coordinatesPath.parent.createDirsIfNeededAs(user.osUsername)
			coordinatesPath.writerAs(user.osUsername).use { writerCoords ->
				writerCoords.write("image_name\tx_coord\ty_coord\n")

				// TODO: could optimize this query to project just the micrographIds if needed
				Micrograph.getAllAsync(jobId) { cursor ->
					for (micrograph in cursor) {
						val particles = Database.particles.getParticles2D(jobId, particlesList.name, micrograph.micrographId)
						if (particles.isNotEmpty()) {

							writerMicrographs.write("${micrograph.micrographId}\t${micrograph.micrographId}.mrc\n")
							val boxFile = nextDir / "${micrograph.micrographId}.next"
							boxFile.parent.createDirsIfNeededAs(user.osUsername)
							boxFile.writerAs(user.osUsername).use { writerBox ->
								for (particleId in particles.keys.sorted()) {
									val particle = particles[particleId]
										?: continue
									// TODO: should we write the particle radius?
									writerCoords.write("${micrograph.micrographId}\t${particle.x}\t${particle.y}\n")
									writerBox.write("${particle.x.toInt()}\t${particle.y.toInt()}\n")
								}
							}
						}
					}
				}
			}
		}
	}

	suspend fun writeTomography(user: User, jobId: String, dir: Path, argValues: ArgValues, listName: String?) {

		// get the particles mode
		val tomoVirMethod = argValues.tomoVirMethodOrDefault
		if (tomoVirMethod.usesAutoList) {

			if (tomoVirMethod.isVirusMode) {

				// in auto virus mode, just write the particle thresholds for the auto virions
				Database.particleLists.get(jobId, ParticlesList.PypAutoVirions)
					?.let { writeTomography(user, jobId, dir, it) }
				// NOTE: write() also writes out coordinates, but that's not so bad in this case
			}

		} else if (argValues.tomoSpkMethodOrDefault.usesAutoList) {

			// in auto particles mode, don't write anything

		} else if (listName != null) {

			// otherwise, write out the coordinates and thresholds for the picked particles
			Database.particleLists.get(jobId, listName)
				?.let { writeTomography(user, jobId, dir, it) }
		}
	}

	private suspend fun writeTomography(user: User, jobId: String, dir: Path, particlesList: ParticlesList) {

		val trainDir = dir / "train"
		val nextDir = dir / "next"

		// write out the particles name
		val pathListFile = trainDir / "current_list.txt"
		pathListFile.parent.createDirsIfNeededAs(user.osUsername)
		val particlesFilename = particlesList.name
			.replace(" ", "_")
			.replace("$", "_")
			.replace(".", "_")
		pathListFile.writeStringAs(user.osUsername, particlesFilename)

		// write out the tilt series image paths and coordinates
		val pathFile = trainDir / "${particlesFilename}_images.txt"
		pathFile.parent.createDirsIfNeededAs(user.osUsername)
		pathFile.writerAs(user.osUsername).use { writerPaths ->
			writerPaths.write("image_name\trec_path\n")

			val coordinatesFile = trainDir / "${particlesFilename}_coordinates.txt"
			coordinatesFile.parent.createDirsIfNeededAs(user.osUsername)
			coordinatesFile.writerAs(user.osUsername).use { writerCoords ->
				writerCoords.write("image_name\tx_coord\tz_coord\ty_coord\n")
				// NOTE: the x,z,y order for the coords file is surprising but apparently intentional

				val file = nextDir / "virion_thresholds.next"
				file.parent.createDirsIfNeededAs(user.osUsername)
				file.writerAs(user.osUsername).use { writerThresholds ->

					// TODO: could optimize this query to project just the tiltSeriesIds if needed
					TiltSeries.getAllAsync(jobId) { cursor ->
						for (tiltSeries in cursor) {

							val particles = Database.particles.getParticles3D(jobId, particlesList.name, tiltSeries.tiltSeriesId)
							val thresholds = Database.particles.getVirionThresholds(jobId, particlesList.name, tiltSeries.tiltSeriesId)
							if (particles.isNotEmpty()) {

								writerPaths.write("${tiltSeries.tiltSeriesId}\t$dir/mrc/${tiltSeries.tiltSeriesId}.rec\n")

								// track the write order of the particle coordinates (call it the particleIndex)
								// so the thresholds writer can refer to the index again later
								val indicesById = HashMap<Int,Int>()

								val boxFile = nextDir / "${tiltSeries.tiltSeriesId}.next"
								boxFile.parent.createDirsIfNeededAs(user.osUsername)
								boxFile.writerAs(user.osUsername).use { writerBox ->

									for ((particleIndex, particleId) in particles.keys.sorted().withIndex()) {
										val particle = particles[particleId]
											?: continue
										indicesById[particleId] = particleIndex
										// TODO: are these supposed to be truncated to integers?
										writerCoords.write("${tiltSeries.tiltSeriesId}\t${particle.x.toInt()}\t${particle.z.toInt()}\t${particle.y.toInt()}\n")
										writerBox.write("${particle.x.toInt()}\t${particle.y.toInt()}\t${particle.z.toInt()}\n")
									}
								}

								// TODO - COORDINATES PRODUVED BY THE WEBSITE IN Z WOULD NOT NEED TO BE BINNED BY 2

								// Send a virion threshold for each particle, even for virions with no threshold.
								// If there's no threshold for a particle, send a sentinal value of 9 instead.
								// And if there are no thresholds in the whole job, just write an empty file.
								for (particleId in particles.keys) {
									val particleIndex = indicesById[particleId]
									val threshold = thresholds[particleId]
										?: 9 // no threshold, use sentinel value instead of null
									writerThresholds.write("${tiltSeries.tiltSeriesId}\t$particleIndex\t$threshold\n")
								}
							}
						}
					}
				}
			}
		}
	}
}
