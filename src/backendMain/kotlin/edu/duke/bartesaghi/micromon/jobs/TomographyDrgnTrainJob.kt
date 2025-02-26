package edu.duke.bartesaghi.micromon.jobs

import com.mongodb.client.model.Updates
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.nodes.TomographyDrgnTrainNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import org.bson.conversions.Bson
import org.slf4j.LoggerFactory


class TomographyDrgnTrainJob(
	userId: String,
	projectId: String
) : Job(userId, projectId, config) {

	val args = JobArgs<TomographyDrgnTrainArgs>()

	var inMovieRefinements: CommonJobData.DataId? by InputProp(config.inMovieRefinements)

	companion object : JobInfo {

		override val config = TomographyDrgnTrainNodeConfig
		override val dataType = JobInfo.DataType.TiltSeries
		override val dataClass = TomographyDrgnTrainData::class

		override fun fromDoc(doc: Document) = TomographyDrgnTrainJob(
			doc.getString("userId"),
			doc.getString("projectId")
		).apply {
			args.finished = doc.getDocument("finishedArgs")?.let { TomographyDrgnTrainArgs.fromDoc(it) }
			args.next = doc.getDocument("nextArgs")?.let { TomographyDrgnTrainArgs.fromDoc(it) }
			fromDoc(doc)
		}

		private fun TomographyDrgnTrainArgs.toDoc() = Document().also { doc ->
			doc["values"] = values
		}

		private fun TomographyDrgnTrainArgs.Companion.fromDoc(doc: Document) =
			TomographyDrgnTrainArgs(
				doc.getString("values")
			)

		class EventListeners {

			private val log = LoggerFactory.getLogger("EventListeners[${config.id}]")

			inner class Listener(val jobId: String) : AutoCloseable {

				var onConvergence: (suspend (TomoDrgnConvergence) -> Unit)? = null

				override fun close() {
					listenersByJob[jobId]?.remove(this)
				}
			}

			private val listenersByJob = HashMap<String,MutableList<Listener>>()

			fun add(jobId: String) =
				Listener(jobId).also {
					listenersByJob.getOrPut(jobId) { ArrayList() }.add(it)
				}

			suspend fun sendConvergence(jobId: String, convergence: TomoDrgnConvergence) {
				listenersByJob[jobId]?.forEach { listener ->
					try {
						listener.onConvergence?.invoke(convergence)
					} catch (ex: Throwable) {
						log.error("convergence listener failed", ex)
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
	}

	override fun updateDoc(updates: MutableList<Bson>) {
		super.updateDoc(updates)
		updates.add(Updates.set("finishedArgs", args.finished?.toDoc()))
		updates.add(Updates.set("nextArgs", args.next?.toDoc()))
	}

	override fun isChanged() = args.hasNext()

	override suspend fun data() =
		TomographyDrgnTrainData(
			commonData(),
			args,
			diagramImageURL()
		)

	fun convergenceParameters(): TomoDrgnConvergence.Parameters? =
		pypParameters()?.let {
			TomoDrgnConvergence.Parameters(
				epochs = it.tomodrgnVaeTrainEpochs,
				finalMaxima = it.tomodrgnVaeConvergenceFinalMaxima,
				epochInterval = it.tomodrgnVaeConvergenceEpochInterval
			)
		}

	fun convergence(params: TomoDrgnConvergence.Parameters) = TomoDrgnConvergence(
		parameters = params,
		iterations = Database.instance.tomoDrgnConvergence.getAll(idOrThrow)
			.sortedBy { it.epoch }
	)

	val eventListeners get() = Companion.eventListeners

	override suspend fun launch(runId: Int) {

		val project = projectOrThrow()

		// clear caches
		wwwDir.recreate()
		Database.instance.tomoDrgnConvergence.deleteAll(idOrThrow)

		// build the args for PYP
		val pypArgs = launchArgValues()
		pypArgs.dataMode = "tomo"

		Pyp.pyp.launch(project, runId, pypArgs, "Launch", "pyp_launch")

		// job was launched, move the args over
		args.run()
		update()
	}

	fun diagramImageURL(): String =
		ITomographyDrgnTrainService.plotPath(idOrThrow, 2)

	override fun wipeData() {

		// also delete any associated data
		Database.instance.tomoDrgnConvergence.deleteAll(idOrThrow)

		// also reset the finished args
		args.unrun()
		update()
	}

	override fun newestArgValues(): ArgValuesToml? =
		args.newest()?.args?.values

	override fun finishedArgValues(): ArgValuesToml? =
		args.finished?.values
}
