package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.cluster.ClusterJobOwner
import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.mongo.SavedParticles
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.sessions.Session
import org.slf4j.LoggerFactory


enum class ParticlesVersion(val number: Int) {

	/**
	 * Complicated rules for particle coordinate binning factors.
	 * Only used when handling legacy data.
	 */
	Legacy(0),

	/**
	 * All particles are always represented in completely unbinned coordinate spaces.
	 * Nice and simple
	 */
	Unbinned(1);


	companion object {

		val DEFAULT = Legacy

		operator fun get(number: Int?): ParticlesVersion =
			values().find { it.number == number }
				?: DEFAULT
	}
}


fun SavedParticles<Particle2DUntyped>.toUnbinned2D(owner: ClusterJobOwner, list: ParticlesList): SavedParticles<Particle2D> =
	when (version) {

		// particles already in unbinned coordinates: just wrap with the safe number types
		// NOTE: legacy micrograph particles were saved in unbinned coordinates too
		ParticlesVersion.Legacy,
		ParticlesVersion.Unbinned -> SavedParticles(
			version = ParticlesVersion.Unbinned,
			saved = mapValues { (_, particle) ->
				Particle2D(
					x = ValueUnbinnedI(particle.x),
					y = ValueUnbinnedI(particle.y),
					r = ValueUnbinnedF(particle.r)
				)
			}
		)
	}

fun SavedParticles<Particle2DUntyped>.toUnbinned2D(job: Job, list: ParticlesList): SavedParticles<Particle2D> =
	toUnbinned2D(ClusterJobOwner.Job(job), list)

fun SavedParticles<Particle2DUntyped>.toUnbinned2D(session: Session, list: ParticlesList): SavedParticles<Particle2D> =
	toUnbinned2D(ClusterJobOwner.Session(session), list)


fun SavedParticles<Particle3DUntyped>.toUnbinned3D(owner: ClusterJobOwner, list: ParticlesList): SavedParticles<Particle3D> =
	when (version) {

		/**
		 * The goal here is to convert whatever is in the database to completely unbinned coordinates.
		 * Hopefully, the pyp parameters are enough to describe the correct coordinate system(s) of the saved particles.
		 * Once we've established what coordinate systems the saved particles are in, then it's just a matter of
		 * applying the correct coordinate transformations to convert to completely unbinned values.
		 */
		ParticlesVersion.Legacy -> {

			val argValues = when (owner) {
				is ClusterJobOwner.Job -> owner.job.pypParametersOrThrow()
				is ClusterJobOwner.Session -> owner.session.pypParametersOrThrow()
			}

			val tomoBinning = argValues.tomoRecBinningOrDefault
			var extraCoordsBinning: Int? = null
			var extraRadiusBinning: Int? = null

			fun noExtraBinning() {}

			// figure out the extra binning
			when (list.type) {

				ParticlesType.Virions3D -> {
					when (argValues.tomoVirMethodOrDefault) {

						TomoVirMethod.None -> noExtraBinning()	

						TomoVirMethod.Auto ->
							argValues.tomoVirBinnOrDefault.toInt().let {
								extraRadiusBinning = it
							}

						// manually-picked virions were saved with extra binning
						TomoVirMethod.Manual ->
							argValues.tomoVirBinnOrDefault.toInt().let {
								extraCoordsBinning = it
								extraRadiusBinning = it
							}

						TomoVirMethod.PYPTrain,
						TomoVirMethod.PYPEval ->
							argValues.tomoVirBinnOrDefault.toInt().let {
								extraCoordsBinning = it
								// NOTE: extra binning does not apply to radius here
							}
					}
				}

				// spikes and regular particles don't have any extra binning
				ParticlesType.Particles3D -> noExtraBinning()

				ParticlesType.Particles2D -> throw IllegalArgumentException("have 3d particles: expected list type != 2d")
			}

			LoggerFactory.getLogger("ParticlesConversion")
				.debug("""
					|Converting legacy tomo particles to unbinned:
					|    owner: {}
					|     list: {}
					|  binning: tomo={}, coords={}, radius={} 
				""".trimMargin(), owner, list, tomoBinning, extraCoordsBinning, extraRadiusBinning)

			// finally, convert the particles
			SavedParticles(
				version = ParticlesVersion.Unbinned,
				saved = mapValues { (_, particle) ->

					fun ValueBinnedI.convertCoord(): ValueUnbinnedI =
						withoutExtraBinning(extraCoordsBinning ?: 1)
							.toUnbinned(tomoBinning)

					fun ValueBinnedF.convertRadius(): ValueUnbinnedF =
						withoutExtraBinning(extraRadiusBinning ?: 1)
							.toUnbinned(tomoBinning)

					Particle3D(
						ValueBinnedI(particle.x).convertCoord(),
						ValueBinnedI(particle.y).convertCoord(),
						ValueBinnedI(particle.z).convertCoord(),
						ValueBinnedF(particle.r).convertRadius()
					)
				}
			)
		}

		// particles already in unbinned coordinates: just wrap with the safe number types
		ParticlesVersion.Unbinned ->
			SavedParticles(
				version = ParticlesVersion.Unbinned,
				saved = saved.mapValues { (_, particle) ->
					Particle3D(
						ValueUnbinnedI(particle.x),
						ValueUnbinnedI(particle.y),
						ValueUnbinnedI(particle.z),
						ValueUnbinnedF(particle.r)
					)
				}
			)
	}

fun SavedParticles<Particle3DUntyped>.toUnbinned3D(job: Job, list: ParticlesList): SavedParticles<Particle3D> =
	toUnbinned3D(ClusterJobOwner.Job(job), list)

fun SavedParticles<Particle3DUntyped>.toUnbinned3D(session: Session, list: ParticlesList): SavedParticles<Particle3D> =
	toUnbinned3D(ClusterJobOwner.Session(session), list)
