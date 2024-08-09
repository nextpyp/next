package edu.duke.bartesaghi.micromon.mongo.migrations

import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getMap
import edu.duke.bartesaghi.micromon.mongo.useCursor
import edu.duke.bartesaghi.micromon.pyp.aToUnbinned
import edu.duke.bartesaghi.micromon.pyp.detectRad
import edu.duke.bartesaghi.micromon.pyp.imagesScale
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.slf4j.Logger


/**
 * Migrate the auto particles and the picked particles to the new unified particles system
 */
fun migrationParticles(database: Database, log: Logger) {

	// phase 1: particlepickings
	run {
		var numParticles = 0L
		migrate(database, log, "particlepickings", ParticlesType.Particles2D) { list, doc, radiusUnbinned ->

			// migrate the particles coords for each micrograph
			for ((micrographId, coords) in doc.getMap<List<Document>>("pickings")) {
				// TODO: the micrograph ids from the old system may have been projected? maybe need to unproject here?
				val particles = HashMap<Int,Particle2D>()
				var nextId = 1
				for (coordDoc in coords) {
					particles[nextId] = Particle2D(
						x = coordDoc.getDouble("x"),
						y = coordDoc.getDouble("y"),
						r = radiusUnbinned
					)
					nextId += 1
					numParticles += 1
				}
				database.particles.importParticles2D(list.ownerId, list.name, micrographId, particles)
			}
		}
		log.info("Migrated $numParticles single-particle picked particles successfully.")
	}

	// phase 2: tomoparticlepickings
	run {
		var numParticles = 0L
		migrate(database, log, "tomoparticlepickings", ParticlesType.Particles3D) { list, doc, radiusUnbinned ->

			// migrate the particle coords for each tilt series
			for ((tiltSeriesId, coords) in doc.getMap<List<Document>>("tomopickings")) {
				// TODO: the tilt series ids from the old system may have been projected? maybe need to unproject here?
				val particles = HashMap<Int,Particle3D>()
				var nextId = 1
				for (coordDoc in coords) {
					particles[nextId] = Particle3D(
						x = coordDoc.getDouble("x"),
						y = coordDoc.getDouble("y"),
						z = coordDoc.getDouble("z"),
						r = radiusUnbinned
					)
					nextId += 1
					numParticles += 1
				}
				database.particles.importParticles3D(list.ownerId, list.name, tiltSeriesId, particles)
			}
		}
		log.info("Migrated $numParticles tomography picked particles successfully.")
	}
}


private fun migrate(
	database: Database,
	log: Logger,
	collectionName: String,
	particlesType: ParticlesType,
	block: (list: ParticlesList, doc: Document, radiusUnbinned: Double) -> Unit
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

			// get the particle radius from the job,
			// since the old particle system didn't save the radius with the particles
			val argValues = job.pypParametersOrNewestArgs()
			val radiusUnbinned = argValues
				.detectRad
				?.aToUnbinned(argValues.imagesScale())
			if (radiusUnbinned == null) {
				log.info("Failed to find particle radius for job=$jobId. Skipping this job")
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

			block(list, doc, radiusUnbinned)
		}
	}
}
