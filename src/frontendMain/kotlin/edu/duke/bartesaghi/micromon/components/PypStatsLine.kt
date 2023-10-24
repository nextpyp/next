package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.services.PypStats
import io.kvision.html.Div
import io.kvision.html.span


class PypStatsLine(stats: PypStats) : Div(classes = setOf("pyp-stats")) {

	var stats: PypStats = stats
		set(value) {
			field = value
			update()
		}

	init {
		update()
	}

	private fun update() {
		removeAll()
		span("Voltage ${stats.scopeVoltage} kV")
		span(", Pixel size ${stats.scopePixel} A")
		span(", Dose rate ${stats.scopeDoseRate}")
		span(" e<sup>-</sup>/A<sup>2</sup>", rich=true) // NOTE: don't use richness for external inputs! that way leads to injection attacks
		span(", Particle radius ${stats.particleRadius} A")
	}
}
