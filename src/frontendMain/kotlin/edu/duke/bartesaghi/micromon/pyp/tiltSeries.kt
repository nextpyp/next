package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.diagram.nodes.NodeClientInfo
import edu.duke.bartesaghi.micromon.services.*


data class TiltSeriesesData(
	val tiltSerieses: MutableList<TiltSeriesData> = ArrayList(),
	var imagesScale: ImagesScale? = null,
	var detectMode: DetectModeData? = null,
	var virusMode: VirusModeData? = null,
	var spikeMode: SpikeModeData? = null
) {

	suspend fun loadForProject(jobId: String, nodeClientInfo: NodeClientInfo, finishedValues: ArgValuesToml?) {

		tiltSerieses.clear()
		Services.jobs.getTiltSerieses(jobId)
			.sortedBy { it.timestamp }
			.forEach { tiltSerieses.add(it) }

		imagesScale = Services.jobs.getImagesScale(jobId)
			.unwrap()

		// get the job's finished pyp arg values, if any
		val args = nodeClientInfo.pypArgs.get()
		val values = finishedValues?.toArgValues(args)

		// get the detect mode data, if any
		detectMode = values
			?.takeIf { values.args.detectMethodExists && it.detectMethodOrDefault.isEnabled }
			?. let {
				DetectModeData(
					particleRadiusA = it.detectRad,
					numAutoParticles = Services.particles.countParticles(OwnerType.Project, jobId, ParticlesList.PypAutoParticles, null)
						.unwrap()
				)
			}

		// get the virion mode data, if any
		virusMode = values
			?.takeIf { values.args.tomoVirMethodExists && it.tomoVirMethodOrDefault.isEnabled }
			?.let {
				VirusModeData(
					virionRadiusA = it.tomoVirRadOrDefault,
					virionBinning = it.tomoVirBinnOrDefault.toInt(),
					numAutoVirions = Services.particles.countParticles(OwnerType.Project, jobId, ParticlesList.PypAutoVirions, null)
						.unwrap()
				)
			}

		// get the spike mode data, if any
		spikeMode = values
			?.takeIf { values.args.tomoSpkMethodExists && it.tomoSpkMethodOrDefault.isEnabled }
			?.let {
				SpikeModeData(
					spikeRadiusA = it.tomoSpkRadOrDefault
				)
			}
	}

	suspend fun loadForSession(session: SessionData, initMsg: RealTimeS2C.SessionStatus, dataMsg: RealTimeS2C.SessionLargeData) {

		tiltSerieses.clear()
		tiltSerieses.addAll(dataMsg.tiltSerieses)

		imagesScale = initMsg.imagesScale

		// get the detect mode data, if any
		detectMode = DetectModeData(
			particleRadiusA = null, // TODO: do we need to get this from somewhere?
			numAutoParticles = Services.particles.countParticles(OwnerType.Session, session.sessionId, ParticlesList.PypAutoParticles, null)
				.unwrap()
		)

		// get the virion mode data, if any
		virusMode = initMsg
			.takeIf { initMsg.tomoVirMethod.isEnabled }
			?.let {
				VirusModeData(
					virionRadiusA = it.tomoVirRad,
					virionBinning = it.tomoVirBinn.toInt(),
					numAutoVirions = Services.particles.countParticles(OwnerType.Session, session.sessionId, ParticlesList.PypAutoVirions, null)
						.unwrap()
				)
			}
	}

	fun update(msg: RealTimeS2C.UpdatedTiltSeries) =
		update(msg.tiltSeries, msg.numAutoParticles, msg.numAutoVirions)

	fun update(msg: RealTimeS2C.SessionTiltSeries) =
		update(msg.tiltSeries, msg.numAutoParticles, msg.numAutoVirions)

	private fun update(tiltSeries: TiltSeriesData, numAutoParticles: Long?, numAutoVirions: Long?) {

		// update the main tilt series list, if needed
		val index = tiltSerieses.indexOfFirst { it.id == tiltSeries.id }
		if (index >= 0) {
			tiltSerieses[index] = tiltSeries
		} else {
			tiltSerieses.add(tiltSeries)
		}

		// update the auto counts

		// basically this: numAutoParticles += msg.numAutoParticles
		detectMode?.numAutoParticles = detectMode?.numAutoParticles?.let { dst ->
			numAutoParticles?.let { src ->
				dst + src
			}
		}

		// basically this: virusMode.numAutoVirions += msg.numAutoVirions
		virusMode?.numAutoVirions = virusMode?.numAutoVirions?.let { dst ->
			numAutoVirions?.let { src ->
				dst + src
			}
		}
	}
}


data class DetectModeData(
	var particleRadiusA: Double?,
	/** for older combined preprocessing blocks */
	var numAutoParticles: Long?
)


data class VirusModeData(
	val virionRadiusA: Double,
	var virionBinning: Int,
	/** for older combined preprocessing blocks */
	var numAutoVirions: Long?
)


data class SpikeModeData(
	val spikeRadiusA: Double
)
