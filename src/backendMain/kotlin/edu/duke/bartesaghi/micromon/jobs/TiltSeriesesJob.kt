package edu.duke.bartesaghi.micromon.jobs


interface TiltSeriesesJob {

	var latestTiltSeriesId: String?

	suspend fun notifyTiltSeries(tiltSeriesId: String)
}
