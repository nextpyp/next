package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.formatWithDigitGroupsSeparator
import edu.duke.bartesaghi.micromon.services.JobData
import io.kvision.core.Container
import io.kvision.html.div


/**
 * Adds the metadata for a job to the receiver container as individual div elements,
 * one per line — each becomes its own row in the block view bar's CSS grid,
 * matching the multi-row layout used by TiltSeriesStats + PypStatsLine.
 * Mirrors the metadata shown on the block's card in the project gallery view.
 */
fun Container.addJobMetaInfo(job: JobData) {
	val d = job.asDynamic()

	val infoString = d.jobInfoString as? String
	if (!infoString.isNullOrBlank()) {
		// Detect the refinement format: "Iteration N: X from Y projections"
		val colonIdx = infoString.indexOf(':')
		if (colonIdx > 0 && infoString.startsWith("Iteration ")) {
			val iterationNumber = infoString.substring("Iteration ".length, colonIdx).trim()
			val projectionPart = infoString.substring(colonIdx + 1).trim()
			div("Map(s): $projectionPart", classes = setOf("job-meta-info"))
			div("Iteration(s): $iterationNumber", classes = setOf("job-meta-info"))
		} else {
			for (part in infoString.split(":").reversed()) {
				val trimmed = part.trim()
				if (trimmed.isNotEmpty()) div(trimmed, classes = setOf("job-meta-info"))
			}
		}
	}

	val numMicrographs = (d.numMicrographs as? Number)?.toLong()
	if (numMicrographs != null) {
		div("${numMicrographs.formatWithDigitGroupsSeparator()} micrograph(s)", classes = setOf("job-meta-info"))
	}

	val numParticles = (d.numParticles as? Number)?.toLong()
	if (numParticles != null) {
		div("${numParticles.formatWithDigitGroupsSeparator()} particle(s)", classes = setOf("job-meta-info"))
	}

	val numTiltSeries = (d.numTiltSeries as? Number)?.toLong()
	if (numTiltSeries != null) {
		div("$numTiltSeries tilt-series", classes = setOf("job-meta-info"))
	}
}
