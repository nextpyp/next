package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.diagram.nodes.*
import edu.duke.bartesaghi.micromon.services.*


data class TiltSeriesesData(
	val tiltSerieses: MutableList<TiltSeriesData> = ArrayList(),
	var particles: TiltSeriesesParticlesData? = null
) {

	suspend fun loadForProject(job: JobData, finishedValues: ArgValuesToml?) {

		// load the tilt series
		tiltSerieses.clear()
		Services.jobs.getTiltSerieses(job.jobId)
			// sort tilt series by id, then timestamp
			// that way, the overall sort is by timestamp, but ids can break ties if for some reason tilt series have the same timestamp
			// (since sortedBy() claims to be a stable sort, this should work)
			.sortedBy { it.id }
			.sortedBy { it.timestamp }
			.forEach { tiltSerieses.add(it) }

		// get the job's finished pyp arg values, if any
		val args = job.clientInfo.pypArgs.get()
		val values = finishedValues?.toArgValues(args)
			?: return

		// determine the mode for particles
		when (job) {

			// newer blocks have simple rules
			is TomographyPickingData ->
				particles = values.tomoPickMethodOrDefault.particlesList(job.jobId)?.let { list ->
					TiltSeriesesParticlesData.Data(
						list,
						radius = values.tomoPickRadOrDefault
					)
				}

			is TomographyParticlesEvalData,
			is TomographySegmentationClosedData ->
				particles = TiltSeriesesParticlesData.Data(ParticlesList.autoParticles3D(job.jobId))

			is TomographyPickingClosedData,
			is TomographyPickingOpenData ->
				particles = values.tomoSrfMethodOrDefault.particlesList(job.jobId)?.let { list ->
					TiltSeriesesParticlesData.Data(list)
				}

			// older blocks had complicated rules
			is TomographyPreprocessingData,
			is TomographyImportDataData,
			is TomographySessionDataData -> combinedRules(
				job.jobId,
				values.tomoVirMethodOrDefault,
				values.tomoVirRadOrDefault,
				values.tomoVirBinnOrDefault.toInt(),
				values.tomoVirDetectMethodOrDefault,
				values.tomoSpkMethodOrDefault,
				values.tomoSpkRadOrDefault
			)

			// other blocks don't have particles
			else -> Unit
		}

		// since loading particles list is a complicated and error-prone process,
		// let's just always print debug info here about what got loaded
		console.log("Loaded tilt series data: particles=", particles)
	}

	private fun combinedRules(
		ownerId: String,
		tomoVirMethod: TomoVirMethod,
		tomoVirRad: ValueA,
		tomoVirBinn: Int,
		tomoVirDetectMethod: TomoVirDetectMethod,
		tomoSpkMethod: TomoSpkMethod,
		tomoSpkRad: ValueA
	) {

		// check for virus mode
		val virionsList = tomoVirMethod.particlesList(ownerId)
		if (virionsList != null) {

			particles = TiltSeriesesParticlesData.VirusMode(
				virions = TiltSeriesesParticlesData.Data(
					list = virionsList,
					radius = tomoVirRad,
					extraBinning = null
				),
				spikes = tomoVirDetectMethod.particlesList(ownerId)?.let { list ->
					TiltSeriesesParticlesData.Data(list)
				}
			)

		} else {

			particles = tomoSpkMethod.particlesList(ownerId)?.let { list ->
				TiltSeriesesParticlesData.Data(
					list,
					radius = tomoSpkRad
				)
			}
		}
	}

	fun loadForSession(session: SessionData, initMsg: RealTimeS2C.SessionStatus, dataMsg: RealTimeS2C.SessionLargeData) {

		// collect all the tilt series
		tiltSerieses.clear()
		tiltSerieses.addAll(dataMsg.tiltSerieses)

		// sessions work like combined mode project blocks
		combinedRules(
			session.sessionId,
			initMsg.tomoVirMethod,
			initMsg.tomoVirRad,
			initMsg.tomoVirBinn.toInt(),
			initMsg.tomoVirDetectMethod,
			initMsg.tomoSpkMethod,
			initMsg.tomoSpkRad
		)

		// since loading particles list is a complicated and error-prone process,
		// let's just always print debug info here about what got loaded
		console.log("Loaded tilt series data: particles=", particles)
	}

	fun update(tiltSeries: TiltSeriesData) {

		// update the main tilt series list, if needed
		val index = tiltSerieses.indexOfFirst { it.id == tiltSeries.id }
		if (index >= 0) {
			tiltSerieses[index] = tiltSeries
		} else {
			tiltSerieses.add(tiltSeries)
		}
	}
}


sealed interface TiltSeriesesParticlesData {

	/** used by the older combined preprocessing blocks */
	data class VirusMode(
		val virions: Data,
		val spikes: Data?
	) : TiltSeriesesParticlesData

	data class Data(
		/**
		 * The particles list defined by the particle picking mode,
		 * or null if the mode is none.
		 *
		 * Most of the time, this will point to a list of particles in the database for the owner,
		 * but in the older combined preprocessing blocks, they can have user-defined list names,
		 * so the name of the list stored here may not match any actual list of particles in the database.
		 * In that case, the particles should not be loaded directly from this list name, but the user-chosen
		 * name should be used to load the particles instead.
		 */
		val list: ParticlesList?,
		/**
		 * A radius common to all particles, if such a radius exist.
		 * Otherwise (ie, if all particles have differing radii), set this to null.
		 */
		val radius: ValueA? = null,
		var extraBinning: Int? = null
	) : TiltSeriesesParticlesData
}
