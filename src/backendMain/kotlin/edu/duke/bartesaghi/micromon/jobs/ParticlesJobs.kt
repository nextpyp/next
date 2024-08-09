package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.*
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.ParticlesList
import edu.duke.bartesaghi.micromon.services.ParticlesSource
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.div
import kotlin.io.path.extension


object ParticlesJobs {

	private fun trainDir(jobDir: Path): Path =
		jobDir / "train"

	private fun nextDir(jobDir: Path): Path =
		jobDir / "next"

	private fun listFile(jobDir: Path): Path =
		trainDir(jobDir) / "current_list.txt"

	/**
	 * Regardless of what name the user chose, use the name "particles" for
	 * all particles lists when writing to the filesystem.
	 * Only one particle list needs to exist in a job folder at a time,
	 * so there shouldn't be any name collisions.
	 */
	private fun particlesKey(): String =
		"particles"

	private fun imagesFile(jobDir: Path): Path =
		trainDir(jobDir) / "${particlesKey()}_images.txt"

	private fun coordsFile(jobDir: Path): Path =
		trainDir(jobDir) / "${particlesKey()}_coordinates.txt"

	private fun boxFile(jobDir: Path, datumId: String): Path =
		nextDir(jobDir) / "${datumId}.next"

	suspend fun clear(osUsername: String?, dir: Path) {

		// cleanup all the files written by the write functions
		listFile(dir).deleteAs(osUsername)
		imagesFile(dir).deleteAs(osUsername)
		coordsFile(dir).deleteAs(osUsername)
		thresholdsFile(dir).deleteAs(osUsername)

		// and all the next/datumId.next files too
		// but actually delete all the next/*.next files instead,
		// since we don't have easy access to the precise list of micrograhs/tilt series ids anymore
		nextDir(dir)
			.takeIf { it.exists() }
			?.listFolderFastAs(osUsername)
			?.map { Paths.get(it.name) }
			?.filter { it.extension == "next" }
			?.map { it.baseName() }
			?.forEach { boxFile(dir, it).deleteAs(osUsername) }
	}

	suspend fun writeSingleParticle(osUsername: String?, jobId: String, dir: Path, particlesList: ParticlesList) {

		// write the name of the picked particles list
		val listFile = listFile(dir)
		listFile.parent.createDirsIfNeededAs(osUsername)
		listFile.writeStringAs(osUsername, particlesKey())

		// write out the micrographs and coordinates
		val imagesFile = imagesFile(dir)
		imagesFile.parent.createDirsIfNeededAs(osUsername)
		imagesFile.writerAs(osUsername).use { writerImages ->
			writerImages.write("image_name\tpath\n")

			val coordsFile = coordsFile(dir)
			coordsFile.parent.createDirsIfNeededAs(osUsername)
			coordsFile.writerAs(osUsername).use { writerCoords ->
				writerCoords.write("image_name\tx_coord\ty_coord\n")

				// TODO: could optimize this query to project just the micrographIds if needed
				Micrograph.getAllAsync(jobId) { cursor ->
					for (micrograph in cursor) {
						val particles = Database.particles.getParticles2D(jobId, particlesList.name, micrograph.micrographId)
						if (particles.isNotEmpty()) {

							writerImages.write("${micrograph.micrographId}\t${micrograph.micrographId}.mrc\n")
							val boxFile = boxFile(dir, micrograph.micrographId)
							boxFile.parent.createDirsIfNeededAs(osUsername)
							boxFile.writerAs(osUsername).use { writerBox ->
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

	suspend fun writeTomography(osUsername: String?, jobId: String, dir: Path, argValues: ArgValues, listName: String? = null) {

		// first, look for virions (only used by older combined preprocessing blocks)
		argValues.tomoVirMethodOrDefault.particlesList(jobId)
			?.let { list ->
				when (list.source) {

					ParticlesSource.User -> {
						// in manual virus mode, write the virion coordinates
						// NOTE: this will also write out empty thresholds, but that's ok
						if (listName == null) {
							throw IllegalArgumentException("manually-picked virions requires a list name")
						}
						writeTomography(osUsername, jobId, dir, list.copy(name = listName))
					}

					ParticlesSource.Pyp -> {
						// in auto virus mode, just write the particle thresholds for the virions
						// NOTE: this will also write out the coordinates, but that's ok
						writeTomography(osUsername, jobId, dir, list)
					}
				}
				return
			}

		// next, look for particles
		argValues.tomoSpkMethodOrDefault.particlesList(jobId)
			?.let { list ->
				when (list.source) {

					ParticlesSource.User -> {
						// for manually picked particles, write out the coordinates
						// use list name if provided (it will only be provided by the older combined preprocessing blocks)
						if (listName != null) {
							writeTomography(osUsername, jobId, dir, list.copy(name = listName))
						} else {
							writeTomography(osUsername, jobId, dir, list)
						}
					}

					ParticlesSource.Pyp -> Unit // don't write anything for auto particles
				}
				return
			}

		// NOTE: we don't have to write out anything for segmented particles, since they always run in auto mode
	}

	private fun thresholdsFile(jobDir: Path): Path =
		nextDir(jobDir) / "virion_thresholds.next"

	private suspend fun writeTomography(osUsername: String?, jobId: String, dir: Path, particlesList: ParticlesList) {

		// write out the particles name
		val listFile = listFile(dir)
		listFile.parent.createDirsIfNeededAs(osUsername)
		listFile.writeStringAs(osUsername, particlesKey())

		// write out the tilt series image paths and coordinates
		val imagesFile = imagesFile(dir)
		imagesFile.parent.createDirsIfNeededAs(osUsername)
		imagesFile.writerAs(osUsername).use { writerImages ->
			writerImages.write("image_name\trec_path\n")

			val coordsFile = coordsFile(dir)
			coordsFile.parent.createDirsIfNeededAs(osUsername)
			coordsFile.writerAs(osUsername).use { writerCoords ->
				writerCoords.write("image_name\tx_coord\tz_coord\ty_coord\n")
				// NOTE: the x,z,y order for the coords file is surprising but apparently intentional

				val thresholdsFile = thresholdsFile(dir)
				thresholdsFile.parent.createDirsIfNeededAs(osUsername)
				thresholdsFile.writerAs(osUsername).use { writerThresholds ->

					// TODO: could optimize this query to project just the tiltSeriesIds if needed
					TiltSeries.getAllAsync(jobId) { cursor ->
						for (tiltSeries in cursor) {

							val particles = Database.particles.getParticles3D(jobId, particlesList.name, tiltSeries.tiltSeriesId)
							val thresholds = Database.particles.getVirionThresholds(jobId, particlesList.name, tiltSeries.tiltSeriesId)
							if (particles.isNotEmpty()) {

								writerImages.write("${tiltSeries.tiltSeriesId}\t$dir/mrc/${tiltSeries.tiltSeriesId}.rec\n")

								// track the write order of the particle coordinates (call it the particleIndex)
								// so the thresholds writer can refer to the index again later
								val indicesById = HashMap<Int,Int>()

								val boxFile = boxFile(dir, tiltSeries.tiltSeriesId)
								boxFile.parent.createDirsIfNeededAs(osUsername)
								boxFile.writerAs(osUsername).use { writerBox ->

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
