package edu.duke.bartesaghi.micromon.jobs

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.*
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.ParticlesList
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

	private fun nextFile(jobDir: Path, datumId: String): Path =
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
			?.forEach { nextFile(dir, it).deleteAs(osUsername) }
	}

	suspend fun writeSingleParticle(osUsername: String?, job: Job, dir: Path, particlesList: ParticlesList) {

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
				Micrograph.getAllAsync(job.idOrThrow) { cursor ->
					for (micrograph in cursor) {

						val particlesUntyped = Database.particles.getParticles2D(job.idOrThrow, particlesList.name, micrograph.micrographId)
							?.takeIf { it.isNotEmpty() }
							?: continue

						val particles = particlesUntyped.toUnbinned2D(job, particlesList)

						writerImages.write("${micrograph.micrographId}\t${micrograph.micrographId}.mrc\n")
						val nextFile = nextFile(dir, micrograph.micrographId)
						nextFile.parent.createDirsIfNeededAs(osUsername)
						nextFile.writerAs(osUsername).use { writerNext ->
							for (particleId in particles.keys.sorted()) {
								val particle = particles[particleId]
									?: continue
								// write out the particles in unbinned coordinates
								writerCoords.write("${micrograph.micrographId}\t${particle.x.v}\t${particle.y.v}\n")
								// with radii for the .next files
								writerNext.write("${particle.x.v}\t${particle.y.v}\t${particle.r.v}\n")
							}
						}
					}
				}
			}
		}
	}

	private fun thresholdsFile(jobDir: Path): Path =
		nextDir(jobDir) / "virion_thresholds.next"

	suspend fun writeTomography(osUsername: String?, job: Job, dir: Path, particlesList: ParticlesList) {

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
					TiltSeries.getAllAsync(job.idOrThrow) { cursor ->
						for (tiltSeries in cursor) {

							val particlesUntyped = Database.particles.getParticles3D(job.idOrThrow, particlesList.name, tiltSeries.tiltSeriesId)
								?.takeIf { it.isNotEmpty() }
								?: continue

							val particles = particlesUntyped.toUnbinned3D(job, particlesList)
							val thresholds = Database.particles.getThresholds(job.idOrThrow, particlesList.name, tiltSeries.tiltSeriesId)
								?: HashMap()

							writerImages.write("${tiltSeries.tiltSeriesId}\t$dir/mrc/${tiltSeries.tiltSeriesId}.rec\n")

							// track the write order of the particle coordinates (call it the particleIndex)
							// so the thresholds writer can refer to the index again later
							val indicesById = HashMap<Int,Int>()

							val nextFile = nextFile(dir, tiltSeries.tiltSeriesId)
							nextFile.parent.createDirsIfNeededAs(osUsername)
							nextFile.writerAs(osUsername).use { writerNext ->

								for ((particleIndex, particleId) in particles.keys.sorted().withIndex()) {
									val particle = particles[particleId]
										?: continue
									indicesById[particleId] = particleIndex
									// write the particles in unbinned coordinates, with radii
									writerCoords.write("${tiltSeries.tiltSeriesId}\t${particle.x.v}\t${particle.z.v}\t${particle.y.v}\n")
									// with radii for the .next files
									writerNext.write("${particle.x.v}\t${particle.y.v}\t${particle.z.v}\t${particle.r.v}\n")
								}
							}

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


/**
 * Only used by older combined preprocessing blocks that required user-chosen particles list names
 */
interface CombinedManualParticlesJob

/**
 * Used to flag jobs that support manually picked particles
 */
interface ManualParticlesJob {
	fun manualParticlesListName(): String
}
