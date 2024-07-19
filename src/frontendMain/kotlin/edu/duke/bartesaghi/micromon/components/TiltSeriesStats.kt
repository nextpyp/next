package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.formatWithDigitGroupsSeparator
import edu.duke.bartesaghi.micromon.pyp.TiltSeriesesData
import io.kvision.html.Div


class TiltSeriesStats : Div(classes = setOf("tiltseries-stats")) {

	init {
		content = "(loading ...)"
	}

	private fun TiltSeriesesData.totalCount(): String =
		"Total: ${tiltSerieses.size.formatWithDigitGroupsSeparator()} tilt-series"

	fun update(items: List<String>) {
		content = items.joinToString(", ")
	}

	fun updateCombined(data: TiltSeriesesData, pickingControls: ParticleControls? = null) {

		val items = mutableListOf(data.totalCount())

		val numPickedParticles = pickingControls?.numParticles

		val virusMode = data.virusMode
		if (virusMode != null) {
			items.add("${virusMode.numAutoVirions?.formatWithDigitGroupsSeparator() ?: 0} auto virion(s)")
			items.add("${data.detectMode?.numAutoParticles?.formatWithDigitGroupsSeparator() ?: 0} auto spike(s)")
			if (numPickedParticles != null) {
				items.add("${numPickedParticles.formatWithDigitGroupsSeparator()} picked virion(s)")
			}
		} else {
			items.add("${data.detectMode?.numAutoParticles?.formatWithDigitGroupsSeparator() ?: 0} auto particle(s)")
			if (numPickedParticles != null) {
				items.add("${numPickedParticles.formatWithDigitGroupsSeparator()} picked particle(s)")
			}
		}

		update(items)
	}

	fun updateSegmentation(data: TiltSeriesesData, pickingControls: ParticleControls) {

		val items = mutableListOf(
			data.totalCount(),
			"${pickingControls.numParticles.formatWithDigitGroupsSeparator()} virion(s)"
		)

		data.virusMode?.let { virusMode ->
			items.add("Virion radius: ${virusMode.virionRadiusA} A")
		}

		update(items)
	}

	fun updatePicking(data: TiltSeriesesData, pickingControls: ParticleControls) {

		val items = mutableListOf(
			data.totalCount(),
			"${pickingControls.numParticles.formatWithDigitGroupsSeparator()} particles(s)"
		)

		data.detectMode?.let { mode ->
			items.add("Particle radius: ${mode.particleRadiusA} A")
		}
		data.spikeMode?.let { mode ->
			items.add("Spike radius: ${mode.spikeRadiusA} A")
		}

		update(items)
	}
}
