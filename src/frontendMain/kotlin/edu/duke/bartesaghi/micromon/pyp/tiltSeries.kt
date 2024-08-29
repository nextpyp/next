package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.diagram.nodes.NodeClientInfo
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyParticlesEvalNode
import edu.duke.bartesaghi.micromon.services.*


data class TiltSeriesesData(
	val tiltSerieses: MutableList<TiltSeriesData> = ArrayList(),
	var particles: TiltSeriesesParticlesData? = null
) {

	suspend fun loadForProject(jobId: String, nodeClientInfo: NodeClientInfo, finishedValues: ArgValuesToml?) {

		// load the tilt series
		tiltSerieses.clear()
		Services.jobs.getTiltSerieses(jobId)
			// sort tilt series by id, then timestamp
			// that way, the overall sort is by timestamp, but ids can break ties if for some reason tilt series have the same timestamp
			// (since sortedBy() claims to be a stable sort, this should work)
			.sortedBy { it.id }
			.sortedBy { it.timestamp }
			.forEach { tiltSerieses.add(it) }

		// get the job's finished pyp arg values, if any
		val args = nodeClientInfo.pypArgs.get()
		val values = finishedValues?.toArgValues(args)
			?: return

		// determine the mode for particles
		if (values.args.tomoVirMethodExists && values.args.tomoSpkMethodExists) {

			// combined mode, for older preprocessing blocks
			val virionsList = values.tomoVirMethodOrDefault.particlesList(jobId)
			val particlesList = values.tomoSpkMethodOrDefault.particlesList(jobId)

			if (virionsList != null) {

				particles = TiltSeriesesParticlesData.VirusMode(
					virions = TiltSeriesesParticlesData.Data(
						list = virionsList,
						radius = values.tomoVirRadOrDefault,
						extraBinning = values.tomoVirBinnOrDefault.toInt()
					),
					spikes = particlesList?.let {
						TiltSeriesesParticlesData.Data(
							list = it,
							radius = values.tomoSpkRadOrDefault
						)
					}
				)

			} else {

				particles = particlesList?.let {
					TiltSeriesesParticlesData.Data(
						list = it,
						radius = values.tomoSpkRadOrDefault
					)
				}
			}

		} else if (values.args.tomoSpkMethodExists) {

			// regular mode, for the newer particle picking (without segmentation) blocks
			particles = values.tomoSpkMethodOrDefault.particlesList(jobId)?.let {
				TiltSeriesesParticlesData.Data(
					list = it,
					radius = values.tomoSpkRadOrDefault
				)
			}

		} else if (values.args.tomoSrfMethodExists) {

			// regular mode, for the newer particle picking (with segmentation) blocks
			particles = values.tomoSrfMethodOrDefault.particlesList(jobId)?.let {
				TiltSeriesesParticlesData.Data(
					list = it,
					radius = null,
					extraBinning = null // TODO: need extra binning here?
				)
			}

		} else {
			when (nodeClientInfo) {
				is TomographyParticlesEvalNode.Companion -> particles = TiltSeriesesParticlesData.Data(ParticlesList.autoParticles3D(jobId))
				else -> console.warn("No particles for unrecognized block: ${nodeClientInfo.config.id}")
			}
		}
	}

	fun loadForSession(session: SessionData, initMsg: RealTimeS2C.SessionStatus, dataMsg: RealTimeS2C.SessionLargeData) {

		// collect all the tilt series
		tiltSerieses.clear()
		tiltSerieses.addAll(dataMsg.tiltSerieses)

		// sessions work in combined mode
		val virionsList = initMsg.tomoVirMethod.particlesList(session.sessionId)
		val particlesList = initMsg.tomoSpkMethod.particlesList(session.sessionId)

		if (virionsList != null) {

			particles = TiltSeriesesParticlesData.VirusMode(
				virions = TiltSeriesesParticlesData.Data(
					list = virionsList,
					radius = initMsg.tomoVirRad,
					extraBinning = initMsg.tomoVirBinn.toInt()
				),
				spikes = particlesList?.let {
					TiltSeriesesParticlesData.Data(
						list = it,
						radius = initMsg.tomoSpkRad,
						extraBinning = null
					)
				}
			)

		} else {

			particles = particlesList?.let {
				TiltSeriesesParticlesData.Data(
					list = it,
					radius = initMsg.tomoSpkRad,
					extraBinning = null
				)
			}
		}
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
		val radius: ValueA? = null,
		var extraBinning: Int? = null
	) : TiltSeriesesParticlesData
}
