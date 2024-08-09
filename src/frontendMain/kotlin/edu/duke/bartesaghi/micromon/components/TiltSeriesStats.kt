package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.formatWithDigitGroupsSeparator
import edu.duke.bartesaghi.micromon.pyp.TiltSeriesesData
import edu.duke.bartesaghi.micromon.pyp.TiltSeriesesParticlesData
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.html.Div


class TiltSeriesStats : Div(classes = setOf("tiltseries-stats")) {

	private var virionsCount: Long = 0
	private var particlesCount: Long = 0

	init {
		content = "(loading ...)"
	}

	suspend fun loadCounts(data: TiltSeriesesData, ownerType: OwnerType, ownerId: String, list: ParticlesList?) {

		if (list == null) {
			set(data)
			return
		}

		when (data.particles) {

			null -> Unit

			is TiltSeriesesParticlesData.VirusMode -> {

				// list is the virions list
				virionsCount = Services.particles.countParticles(ownerType, ownerId, list.name, null)
					.unwrap()
					?: 0

				// load the spike count from the auto list too
				particlesCount = Services.particles.countParticles(ownerType, ownerId, ParticlesList.AutoParticles, null)
					.unwrap()
					?: 0
			}

			is TiltSeriesesParticlesData.Data -> {
				// just load the particles count
				particlesCount = Services.particles.countParticles(ownerType, ownerId, list.name, null)
					.unwrap()
					?: 0
			}
		}

		update(data)
	}

	fun set(
		data: TiltSeriesesData,
		particles: Long? = null,
		virions: Long? = null
	) {
		virionsCount = virions ?: 0
		particlesCount = particles ?: 0
		update(data)
	}

	fun increment(data: TiltSeriesesData, tiltSeries: TiltSeriesData) {
		virionsCount += tiltSeries.numAutoVirions
		particlesCount += tiltSeries.numAutoParticles
		update(data)
	}

	fun picked(data: TiltSeriesesData, pickingControls: ParticleControls) {

		when (data.particles) {

			null -> Unit

			is TiltSeriesesParticlesData.VirusMode -> {
				// in virus mode, picking only changes the number of virions
				virionsCount = pickingControls.numParticles
			}

			is TiltSeriesesParticlesData.Data -> {
				// otherwise, change the number of particles
				particlesCount = pickingControls.numParticles
			}
		}

		update(data)
	}

	private fun update(data: TiltSeriesesData) {

		val items = ArrayList<String>()

		items.add("Total: ${data.tiltSerieses.size.formatWithDigitGroupsSeparator()} tilt-series")

		when (data.particles) {

			null -> Unit

			is TiltSeriesesParticlesData.VirusMode -> {
				items.add("${virionsCount.formatWithDigitGroupsSeparator()} virion(s)")
				items.add("${particlesCount.formatWithDigitGroupsSeparator()} spike(s)")
			}

			is TiltSeriesesParticlesData.Data -> {
				items.add("${particlesCount.formatWithDigitGroupsSeparator()} particle(s)")
			}
		}

		content = items.joinToString(", ")
	}
}
