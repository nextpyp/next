package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.diagram.nodes.NodeClientInfo
import edu.duke.bartesaghi.micromon.diagram.nodes.clientInfo
import edu.duke.bartesaghi.micromon.services.*


data class TiltSeriesesData(
	val tiltSerieses: MutableList<TiltSeriesData> = ArrayList(),
	var imagesScale: ImagesScale? = null,
	var numAutoParticles: Long? = null,
	var virusMode: VirusModeData? = null
) {

	suspend fun load(job: TomographyPreprocessingData) =
		loadForProject(
			job.jobId,
			job.clientInfo,
			job.args.finished?.values
		)

	suspend fun load(job: TomographyImportDataData) =
		loadForProject(
			job.jobId,
			job.clientInfo,
			job.args.finished?.values
		)

	suspend fun load(job: TomographySessionDataData) =
		loadForProject(
			job.jobId,
			job.clientInfo,
			job.args.finished?.values
		)

	private suspend fun loadForProject(jobId: String, nodeClientInfo: NodeClientInfo, finishedValues: ArgValuesToml?) {

		tiltSerieses.clear()
		Services.jobs.getTiltSerieses(jobId)
			.sortedBy { it.timestamp }
			.forEach { tiltSerieses.add(it) }

		imagesScale = Services.jobs.getImagesScale(jobId)
			.unwrap()

		numAutoParticles = Services.particles.countParticles(OwnerType.Project, jobId, ParticlesList.PypAutoParticles, null)
			.unwrap()

		// get the job's finished pyp arg values, if any
		val args = nodeClientInfo.pypArgs.get()
		val values = finishedValues?.toArgValues(args)

		// not all blocks have the tomo_vir tab, so check and generate a nice error message if it's missing
		if (!args.tomoVirMethodExists) {
			throw NoSuchElementException("Block ${nodeClientInfo.config.id} has no tomo_vir.method configured")
		}

		// get the virion mode data, if any
		virusMode = values
			?.takeIf { it.tomoVirMethodOrDefault.isVirusMode }
			?.let {
				VirusModeData(
					virionRadiusA = it.tomoVirRadOrDefault,
					virionBinning = it.tomoVirBinnOrDefault.toInt(),
					numAutoVirions = Services.particles.countParticles(OwnerType.Project, jobId, ParticlesList.PypAutoVirions, null)
						.unwrap()
				)
			}
	}

	suspend fun loadForSession(session: SessionData, initMsg: RealTimeS2C.SessionStatus, dataMsg: RealTimeS2C.SessionLargeData) {

		tiltSerieses.clear()
		tiltSerieses.addAll(dataMsg.tiltSerieses)

		imagesScale = initMsg.imagesScale

		numAutoParticles = Services.particles.countParticles(OwnerType.Session, session.sessionId, ParticlesList.PypAutoParticles, null)
			.unwrap()

		// get the virion mode data, if any
		virusMode = initMsg
			.takeIf { initMsg.tomoVirMethod.isVirusMode }
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
		this.numAutoParticles = this.numAutoParticles?.let { dst ->
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


data class VirusModeData(
	val virionRadiusA: Double,
	var virionBinning: Int,
	var numAutoVirions: Long?
)
