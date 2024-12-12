package edu.duke.bartesaghi.micromon.mongo.migrations

import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.mongo.*
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.slf4j.Logger


/**
 * Migrate the auto particles and the picked particles to the new unified particles system
 */
fun migrationParticles(database: DatabaseConnection, log: Logger) {

	// phase 1: particlepickings
	run {
		var numParticles = 0L
		migrate(database, log, "particlepickings", ParticlesType.Particles2D) f@{ job, list, doc ->

			// get the particle radius from the job,
			// since the old particle system didn't save the radius with the particles
			val argValues = job.pypParametersOrNewestArgs()
			val radius = argValues
				.detectRad
				?.toUnbinned(argValues.scopePixel ?: ValueA(1.0))
				?: run {
					log.info("Failed to find particle radius for job=${job.idOrThrow}. Skipping this job")
					return@f
				}

			// migrate the particles coords for each micrograph
			for ((micrographId, coords) in doc.getMap<List<Document>>("pickings")) {
				// TODO: the micrograph ids from the old system may have been projected? maybe need to unproject here?
				val particles = HashMap<Int,Particle2D>()
				var nextId = 1
				for (coordDoc in coords) {
					particles[nextId] = Particle2D(
						x = ValueUnbinnedI(coordDoc.getDouble("x").toInt()),
						y = ValueUnbinnedI(coordDoc.getDouble("y").toInt()),
						r = radius
					)
					nextId += 1
					numParticles += 1
				}
				val savedParticles = SavedParticles(
					version = ParticlesVersion.Legacy,
					saved = particles
				)
				database.particles.importParticles2D(list.ownerId, list.name, micrographId, savedParticles)
			}
		}
		log.info("Migrated $numParticles single-particle picked particles successfully.")
	}

	// phase 2: tomoparticlepickings
	run {
		var numParticles = 0L
		migrate(database, log, "tomoparticlepickings", ParticlesType.Particles3D) f@{ job, list, doc ->

			// get the particle radius from the job,
			// since the old particle system didn't save the radius with the particles
			val argValues = job.pypParametersOrNewestArgs()
			val radius = argValues
				.detectRad
				?.toUnbinned(argValues.scopePixel ?: ValueA(1.0))
				?.toBinned(argValues.tomoRecBinningOrDefault)
				?: run {
					log.info("Failed to find particle radius for job=${job.idOrThrow}. Skipping this job")
					return@f
				}

			// migrate the particle coords for each tilt series
			for ((tiltSeriesId, coords) in doc.getMap<List<Document>>("tomopickings")) {
				// TODO: the tilt series ids from the old system may have been projected? maybe need to unproject here?
				val particles = HashMap<Int,Particle3D>()
				var nextId = 1
				for (coordDoc in coords) {
					particles[nextId] = Particle3D(
						// NOTE: these are actually binned coordinates, but the database only allows writing unbinned coordinates now
						//       but this is a migration for super old stuff, so just lie and say the're unbinned coords for now
						//       but put the legacy version in there, so we know to convert them to real unbinned coords later
						x = ValueUnbinnedI(coordDoc.getDouble("x").toInt()),
						y = ValueUnbinnedI(coordDoc.getDouble("y").toInt()),
						z = ValueUnbinnedI(coordDoc.getDouble("z").toInt()),
						r = ValueUnbinnedF(radius.v)
					)
					nextId += 1
					numParticles += 1
				}
				val savedParticles = SavedParticles(
					version = ParticlesVersion.Legacy,
					saved = particles
				)
				database.particles.importParticles3D(list.ownerId, list.name, tiltSeriesId, savedParticles)
			}
		}
		log.info("Migrated $numParticles tomography picked particles successfully.")
	}
}


private fun migrate(
	database: DatabaseConnection,
	log: Logger,
	collectionName: String,
	particlesType: ParticlesType,
	block: (job: Job, list: ParticlesList, doc: Document) -> Unit
) {

	val pickings = database.db.getCollection(collectionName)
	pickings.find().useCursor { cursor ->
		for (doc in cursor) {

			// look up the job info, if any
			val jobId = doc.getString("jobId")
			val job = Job.fromId(jobId)
			if (job == null) {
				log.info("Found old particles for job=$jobId, but job no longer exists. Skipping this job.")
				continue
			}

			// see if any new particles already exist in this list
			val name = doc.getString("name")
			if (database.particleLists.get(jobId, name) != null) {
				log.info("Found old particles for job=$jobId, list=$name, but list already exists with new particles. Skipping this job")
				continue
			}

			// migrate the particles list
			val list = ParticlesList(
				ownerId = jobId,
				name = name,
				type = particlesType,
				source = ParticlesSource.User
			)
			database.particleLists.createIfNeeded(list)

			block(job, list, doc)
		}
	}
}
