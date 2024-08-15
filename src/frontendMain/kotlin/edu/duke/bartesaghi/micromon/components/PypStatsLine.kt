package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.services.PypStats
import io.kvision.html.Div
import io.kvision.html.span
import io.kvision.html.textNode


class PypStatsLine(stats: PypStats? = null) : Div(classes = setOf("pyp-stats")) {

	var stats: PypStats? = stats
		set(value) {
			field = value
			update()
		}

	init {
		update()
	}

	private fun addStat(label: String, value: Any?, unit: String) {

		if (value == null) {
			textNode("$label (unknown)")
			return
		}

		textNode("$label $value ")

		// show the unit using raw HTML, so we can use special formatting
		span(unit, rich=true)
		// NOTE: only use richness for constant inputs!
		// using richness for external or user inputs leads to injection attacks!
	}

	private fun update() {
		removeAll()
		addStat("Voltage", stats?.scopeVoltage, "kV")
		textNode(", ")
		addStat("Pixel size", stats?.scopePixel, "A")
		textNode(", ")
		addStat("Dose rate", stats?.scopeDoseRate, "e<sup>-</sup>/A<sup>2</sup>")
	}
}
