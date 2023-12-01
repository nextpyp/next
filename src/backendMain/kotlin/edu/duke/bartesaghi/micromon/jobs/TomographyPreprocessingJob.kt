package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyPreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson
import org.slf4j.LoggerFactory
import kotlin.io.path.bufferedWriter
import kotlin.io.path.div


class TomographyPreprocessingJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config), FilteredJob {

	val args = JobArgs<TomographyPreprocessingArgs>()
	var latestTiltSeriesId: String? = null

	var inTiltSeries: CommonJobData.DataId? by InputProp(config.tiltSeries)

	companion object : JobInfo {

		override val config = TomographyPreprocessingNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries

		override fun fromDoc(doc: Document) = TomographyPreprocessingJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { TomographyPreprocessingArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographyPreprocessingArgs.fromDoc(it) }
			latestTiltSeriesId = doc.getString("latestTiltSeriesId")
			fromDoc(doc)
		}

		private fun TomographyPreprocessingArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
			doc["tomolist"] = tomolist
		}

		private fun TomographyPreprocessingArgs.Companion.fromDoc(doc: Document) =
			TomographyPreprocessingArgs(
				doc.getString("values"),
				doc.getString("tomolist")
			)

		fun args() =
			Backend.pypArgs
				.filter(config.configId, includeHiddenArgs = false, includeHiddenGroups = true)
				.appendAll(MicromonArgs.slurmLaunch)

		/**
		 * A mechanism to forward tilt series events to websocket clients listening for real-time updates.
		 */
		class EventListeners {

			private val log = LoggerFactory.getLogger("TomographyPreprocessing")

			inner class Listener(val jobId: String) : AutoCloseable {

				var onTiltSeries: (suspend (TiltSeries) -> Unit)? = null
				var onParams: (suspend (ArgValues) -> Unit)? = null

				override fun close() {
					listenersByJob[jobId]?.remove(this)
				}
			}

			private val listenersByJob = HashMap<String,MutableList<Listener>>()

			fun add(jobId: String) =
				Listener(jobId).also {
					listenersByJob.getOrPut(jobId) { ArrayList() }.add(it)
				}

			suspend fun sendTiltSeries(jobId: String, tiltSeriesId: String) {
				val tiltSeries = TiltSeries.get(jobId, tiltSeriesId) ?: return
				listenersByJob[jobId]?.forEach { listener ->
					try {
						listener.onTiltSeries?.invoke(tiltSeries)
					} catch (ex: Throwable) {
						log.error("tilt series listener failed", ex)
					}
				}
			}

			suspend fun sendParams(jobId: String, values: ArgValues) {
				listenersByJob[jobId]?.forEach { listener ->
					try {
						listener.onParams?.invoke(values)
					} catch (ex: Throwable) {
						log.error("params listener failed", ex)
					}
				}
			}
		}
		val eventListeners = EventListeners()
	}

	override fun createDoc(doc: Document) {
		super.createDoc(doc)
		doc["finishedArgs"] = args.finished?.toDoc()
		doc["nextArgs"] = args.next?.toDoc()
		doc["latestTiltSeriesId"] = null
	}

	override fun updateDoc(updates: MutableList<Bson>) {
		super.updateDoc(updates)
		updates.add(Updates.set("finishedArgs", args.finished?.toDoc()))
		updates.add(Updates.set("nextArgs", args.next?.toDoc()))
		updates.add(Updates.set("latestTiltSeriesId", latestTiltSeriesId))
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		TomographyPreprocessingData(
			commonData(),
			args,
			diagramImageURL(),
			Database.tiltSeries.count(idOrThrow),
			Database.particles.countAllParticles(idOrThrow, ParticlesList.PypAutoParticles)
		)

	override suspend fun launch(runId: Int) {

		// clear caches
		clearWwwCache()

		// get the input raw data job
		val prevJob = inTiltSeries?.resolveJob<Job>() ?: throw IllegalStateException("no tilt series input configured")

		// get the particles mode
		val jobArgs = args.newestOrThrow().args
		val argValues = jobArgs.values.toArgValues(Backend.pypArgs)
		val tomoVirMethod = argValues.tomoVirMethodOrDefault
		if (tomoVirMethod.usesAutoList) {

			if (tomoVirMethod.isVirusMode) {

				// in auto virus mode, just write the particle thresholds for the auto virions
				Database.particleLists.get(idOrThrow, ParticlesList.PypAutoVirions)
					?.write()
				// NOTE: write() also writes out coordinates, but that's not so bad in this case
			}

		} else if (argValues.tomoSpkMethodOrDefault.usesAutoList) {

			// in auto particles mode, don't write anything

		} else if (jobArgs.tomolist != null) {

			// otherwise, write out the coordinates and thresholds for the picked particles
			Database.particleLists.get(idOrThrow, jobArgs.tomolist)
				?.write()
		}

		// write out the tilt exclusions, if needed
		run {

			val dir = dir / "next"
			val suffix = "_exclude_views.next"

			// delete any old files
			if (dir.exists()) {
				dir.listFiles()
					.filter { it.fileName.toString().endsWith(suffix) }
					.forEach { it.delete() }
			}

			// write any new files
			val exclusionsByTiltSeries = Database.tiltExclusions.getForJob(idOrThrow)
			if (exclusionsByTiltSeries != null) {
				for ((tiltSeriesId, exclusionsByTiltIndex) in exclusionsByTiltSeries) {
					val file = dir / "$tiltSeriesId$suffix"
					file.parent.createDirsIfNeeded()
					file.bufferedWriter().use { writer ->
						for ((tiltIndex, isExcluded) in exclusionsByTiltIndex) {
							if (isExcluded) {
								writer.write("0\t0\t$tiltIndex\n")
							}
						}
					}
				}
			}
		}

		// build the args for PYP
		val pypArgs = ArgValues(Backend.pypArgs)

		// copy args from the raw data block, since pyp requires them
		pypArgs.setAll(prevJob.finishedArgValuesOrThrow())

		// set the user args
		pypArgs.setAll(args().diff(
			jobArgs.values,
			args.finished?.values ?: prevJob.finishedArgValues()
		))

		// set the hidden args
		pypArgs.dataMode = "tomo"

		Pyp.pyp.launch(runId, pypArgs, "Launch", "pyp_launch")

		// job was launched, move the args over
		args.run()
		// and wipe the latest tilt series id, so we can detect when the first one comes in next time
		latestTiltSeriesId = null
		update()
	}

	private fun ParticlesList.write() {

		// write out the particles name
		val pathListFile = dir / "train" / "current_list.txt"
		pathListFile.parent.createDirsIfNeeded()
		val particlesName = name
			.replace(" ", "_")
			.replace("$", "_")
			.replace(".", "_")
		pathListFile.writeString(particlesName)

		// write out the tilt series image paths and coordinates
		val pathFile = dir / "train" / "${particlesName}_images.txt"
		pathFile.parent.createDirsIfNeeded()
		pathFile.bufferedWriter().use { writerPaths ->
			writerPaths.write("image_name\trec_path\n")

			val coordinatesFile = dir / "train" / "${particlesName}_coordinates.txt"
			coordinatesFile.parent.createDirsIfNeeded()
			coordinatesFile.bufferedWriter().use { writerCoords ->
				writerCoords.write("image_name\tx_coord\tz_coord\ty_coord\n")
				// NOTE: the x,z,y order for the coords file is surprising but apparently intentional

				val file = dir / "next" / "virion_thresholds.next"
				file.parent.createDirsIfNeeded()
				file.bufferedWriter().use { writerThresholds ->

					// TODO: could optimize this query to project just the tiltSeriesIds if needed
					TiltSeries.getAll(idOrThrow) { cursor ->
						for (tiltSeries in cursor) {

							val particles = Database.particles.getParticles3D(idOrThrow, name, tiltSeries.tiltSeriesId)
							val thresholds = Database.particles.getVirionThresholds(idOrThrow, name, tiltSeries.tiltSeriesId)
							if (particles.isNotEmpty()) {

								writerPaths.write("${tiltSeries.tiltSeriesId}\t$dir/mrc/${tiltSeries.tiltSeriesId}.rec\n")

								// track the write order of the particle coordinates (call it the particleIndex)
								// so the thresholds writer can refer to the index again later
								val indicesById = HashMap<Int,Int>()

								val boxFile = dir / "next" / "${tiltSeries.tiltSeriesId}.next"
								boxFile.parent.createDirsIfNeeded()
								boxFile.bufferedWriter().use { writerBox ->

									for ((particleIndex, particleId) in particles.keys.sorted().withIndex()) {
										val particle = particles[particleId]
											?: continue
										indicesById[particleId] = particleIndex
										// TODO: are these supposed to be truncated to integers?
										writerCoords.write("${tiltSeries.tiltSeriesId}\t${particle.x.toInt()}\t${particle.z.toInt()}\t${particle.y.toInt()}\n")
										writerBox.write("${particle.x.toInt()}\t${particle.y.toInt()}\t${particle.z.toInt()}\n")
									}
								}

								// TODO - COORDINATES PRODUVED BY THE WEBSITE IN Z WOULD NOT NEED TO BE BINNED BY 2

								// Send a virion threshold for each particle, even for virions with no threshold.
								// If there's no threshold for a particle, send a sentinal value of 9 instead.
								// And if there are no thresholds in the whole job, just write an empty file.
								for (particleId in particles.keys) {
									val particleIndex = indicesById[particleId]
									val threshold = thresholds[particleId]
										?: 9 // no threshold, use sentinel value instead of null
									writerThresholds.write("${tiltSeries.tiltSeriesId}\t$particleIndex\t$threshold\n")
								}
							}
						}
					}
				}
			}
		}
	}

	fun diagramImageURL(): String {

		val size = ImageSize.Small

		// find an arbitrary (but deterministic) tilt series for this job
		// like the newest tilt series written for this job
		return latestTiltSeriesId
			?.let { "/kv/jobs/$idOrThrow/data/$it/image/${size.id}" }

			// or just use a placeholder
			?: return "/img/placeholder/${size.id}"
	}

	override fun wipeData() {

		// also delete any associated data
		Database.tiltSeriesAvgRot.deleteAll(idOrThrow)
		Database.tiltSeriesDriftMetadata.deleteAll(idOrThrow)
		Database.jobPreprocessingFilters.deleteAll(idOrThrow)
		Database.particleLists.deleteAll(idOrThrow)
		Database.particles.deleteAllParticles(idOrThrow)
	}

	override val filters get() = PreprocessingFilters.ofJob(idOrThrow)

	override fun resolveFilter(filter: PreprocessingFilter): List<String> =
		FilteredJob.resolveFilterTiltSeries(idOrThrow, filter)

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
