package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.formatWithDigitGroupsSeparator
import edu.duke.bartesaghi.micromon.pyp.TiltSeriesesData
import io.kvision.html.Div


class TiltSeriesStats : Div(classes = setOf("tiltseries-stats")) {

	init {
		content = "(loading ...)"
	}

	fun update(data: TiltSeriesesData, pickingControls: ProjectParticleControls? = null) {

		val items = mutableListOf(
			"Total: ${data.tiltSerieses.size.formatWithDigitGroupsSeparator()} tilt-series",
		)

		val numPickedParticles = pickingControls?.numParticles

		val virusMode = data.virusMode
		if (virusMode != null) {
			items.add("${virusMode.numAutoVirions?.formatWithDigitGroupsSeparator() ?: 0} auto virion(s)")
			items.add("${data.numAutoParticles?.formatWithDigitGroupsSeparator() ?: 0} auto spike(s)")
			if (numPickedParticles != null) {
				items.add("${numPickedParticles.formatWithDigitGroupsSeparator()} picked virion(s)")
			}
		} else {
			items.add("${data.numAutoParticles?.formatWithDigitGroupsSeparator() ?: 0} auto particle(s)")
			if (numPickedParticles != null) {
				items.add("${numPickedParticles.formatWithDigitGroupsSeparator()} picked particle(s)")
			}
		}

		content = items.joinToString(", ")
	}
}
