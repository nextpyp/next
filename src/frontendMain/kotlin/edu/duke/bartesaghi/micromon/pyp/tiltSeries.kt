package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.components.indexSearch
import edu.duke.bartesaghi.micromon.services.*


data class TiltSeriesesData(
	val tiltSerieses: MutableList<TiltSeriesData> = ArrayList(),
	var particles: TiltSeriesesParticlesData? = null,
	var finishedValues: ArgValues? = null
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
		// NOTE: we need all the pyp args for this (not just the args for this job)
		//       since we may need to reference some args not in the job to display the particles correctly
		val args = ALL_PYP_ARGS.get()
		val values = finishedValues?.toArgValues(args)
			?: return
		this.finishedValues = values

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

			is TomographyParticlesEvalData ->
				particles = TiltSeriesesParticlesData.Data(ParticlesList.autoParticles3D(job.jobId))

			is TomographySegmentationClosedData ->
				particles = TiltSeriesesParticlesData.VirusMode(
					virions = TiltSeriesesParticlesData.Data(
						list = ParticlesList.autoVirions(job.jobId)
					)
				)

			is TomographyPickingClosedData ->
				particles = TiltSeriesesParticlesData.VirusMode(
					virions = TiltSeriesesParticlesData.Data(
						list = ParticlesList.autoVirions(job.jobId)
					),
					spikes = TiltSeriesesParticlesData.Data(
						list = ParticlesList.autoParticles3D(job.jobId)
					)
				)

			is TomographyPickingOpenData ->
				particles = values.tomoSrfMethodOrDefault.particlesList(job.jobId)?.let { list ->
					TiltSeriesesParticlesData.Data(list)
				}

			// older blocks had complicated rules
			is TomographyPreprocessingData,
			is TomographyImportDataData,
			is TomographySessionDataData -> combinedRules(job.jobId, values)

			// other blocks don't have particles
			else -> Unit
		}

		// since loading particles list is a complicated and error-prone process,
		// let's just always print debug info here about what got loaded
		console.log("Loaded tilt series data: particles=", particles)
	}

	private fun combinedRules(ownerId: String, values: ArgValues) {

		// check for virus mode
		val virionsList = values.tomoVirMethodOrDefault.particlesList(ownerId)
		if (virionsList != null) {

			particles = TiltSeriesesParticlesData.VirusMode(
				virions = TiltSeriesesParticlesData.Data(
					list = virionsList,
					radius = values.tomoVirRad
				),
				spikes = values.tomoVirDetectMethodOrDefault.particlesList(ownerId)?.let { list ->
					TiltSeriesesParticlesData.Data(list)
				}
			)

		} else {

			particles = values.tomoSpkMethodOrDefault.particlesList(ownerId)?.let { list ->
				TiltSeriesesParticlesData.Data(
					list,
					radius = values.tomoSpkRad
				)
			}
		}
	}

	fun loadForSession(session: TomographySessionData, args: Args, dataMsg: RealTimeS2C.SessionLargeData) {

		// collect all the tilt series
		tiltSerieses.clear()
		tiltSerieses.addAll(dataMsg.tiltSerieses)

		// get the newest arg values, if any
		val values = session.args.newest()
			?.args?.values?.toArgValues(args)
		if (values != null) {

			// sessions work like combined mode project blocks
			combinedRules(session.sessionId, values)
		}

		// since loading particles list is a complicated and error-prone process,
		// let's just always print debug info here about what got loaded
		console.log("Loaded tilt series data: particles=", particles)
	}

	fun updateForSession(session: TomographySessionData, args: Args) {

		// get the newest arg values, if any
		val values = session.args.newest()
			?.args?.values?.toArgValues(args)
		if (values != null) {
			combinedRules(session.sessionId, values)
		}

		// since loading particles list is a complicated and error-prone process,
		// let's just always print debug info here about what got loaded
		console.log("Updated tilt series data: particles=", particles)
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

	fun searchById(q: String) =
		tiltSerieses.indexSearch { tiltSeries -> tiltSeries.id.takeIf { q in tiltSeries.id } }
}


sealed interface TiltSeriesesParticlesData {

	/** shows virions and spikes as separate kinds of particles */
	data class VirusMode(
		val virions: Data,
		val spikes: Data? = null
	) : TiltSeriesesParticlesData

	/** shows regular ol' particles */
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
		val radius: ValueA? = null
	) : TiltSeriesesParticlesData
}
