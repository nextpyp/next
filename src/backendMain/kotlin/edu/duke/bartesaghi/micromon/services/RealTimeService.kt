package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.cluster.ClusterJobPermission
import edu.duke.bartesaghi.micromon.cluster.authClusterJobOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.linux.DF
import edu.duke.bartesaghi.micromon.linux.TransferWatcher
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.authJobOrThrow
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import edu.duke.bartesaghi.micromon.projects.ProjectEventListeners
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.sessions.*
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.util.cio.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import java.time.Instant


object RealTimeService {

	fun init(routing: Routing) {

		routing.webSocket(RealTimeServices.project) handler@{

			val user = call.authOrThrow()

			// wait to hear which project the user wants
			val msg = incoming.receiveMessage<RealTimeC2S.ListenToProject>(outgoing) ?: return@handler

			// authenticate the user for this project
			val project = user.authProjectOrThrow(ProjectPermission.Read, msg.userId, msg.projectId)

			// respond with current status of jobs
			val (olderRuns, recentRuns) = project.getRunsPartition()
			outgoing.sendMessage(RealTimeS2C.ProjectStatus(
				recentRuns = recentRuns.map { it.toData() },
				hasOlderRuns = olderRuns.isNotEmpty(),
				blocks = Backend.instance.pypArgs.blocks
			))

			// route job events to the client
			val listenerId = Backend.instance.projectEventListeners.add(
				msg.userId,
				msg.projectId,
				listeners = object : ProjectEventListeners.Listeners {

					// translate job events into websocket messages

					override suspend fun onRunInit(runId: Int, timestamp: Instant, jobIds: List<String>) {
						outgoing.trySendMessage(RealTimeS2C.ProjectRunInit(runId, timestamp.toEpochMilli(), jobIds))
					}

					override suspend fun onRunStart(runId: Int) {
						outgoing.trySendMessage(RealTimeS2C.ProjectRunStart(runId))
					}

					override suspend fun onJobStart(runId: Int, jobId: String) {
						outgoing.trySendMessage(RealTimeS2C.JobStart(runId, jobId))
					}

					override suspend fun onJobUpdate(job: JobData) {
						outgoing.trySendMessage(RealTimeS2C.JobUpdate(job))
					}

					override suspend fun onJobFinish(runId: Int, job: JobData, status: RunStatus, errorMessage: String?) {
						outgoing.trySendMessage(RealTimeS2C.JobFinish(runId, job, status, errorMessage))
					}

					override suspend fun onRunFinish(runId: Int, status: RunStatus) {
						outgoing.trySendMessage(RealTimeS2C.ProjectRunFinish(runId, status))
					}


					// translate cluster job events into websocket messages

					override suspend fun onClusterJobSubmit(runId: Int, jobId: String, clusterJobId: String, clusterJobWebName: String?, arraySize: Int?) {
						outgoing.trySendMessage(RealTimeS2C.ClusterJobSubmit(runId, jobId, clusterJobId, clusterJobWebName, arraySize))
					}

					override suspend fun onClusterJobStart(runId: Int, jobId: String, clusterJobId: String) {
						outgoing.trySendMessage(RealTimeS2C.ClusterJobStart(runId, jobId, clusterJobId))
					}

					override suspend fun onClusterJobStartArray(runId: Int, jobId: String, clusterJobId: String, arrayIndex: Int, numStarted: Int) {
						outgoing.trySendMessage(RealTimeS2C.ClusterJobArrayStart(runId, jobId, clusterJobId, arrayIndex, numStarted))
					}

					override suspend fun onClusterJobFinishArray(runId: Int, jobId: String, clusterJobId: String, arrayIndex: Int, numEnded: Int, numCanceled: Int, numFailed: Int) {
						outgoing.trySendMessage(RealTimeS2C.ClusterJobArrayEnd(runId, jobId, clusterJobId, arrayIndex, numEnded, numCanceled, numFailed))
					}

					override suspend fun onClusterJobFinish(runId: Int, jobId: String, clusterJobId: String, resultType: ClusterJobResultType) {
						outgoing.trySendMessage(RealTimeS2C.ClusterJobEnd(runId, jobId, clusterJobId, resultType))
					}
				}
			)

			// wait for the connection to close, then cleanup the listeners
			incoming.waitForClose(outgoing)
			Backend.instance.projectEventListeners.remove(listenerId)
		}

		suspend fun DefaultWebSocketServerSession.micrographsService() {

			val user = call.authOrThrow()

			// wait to hear which job the user wants
			val msg = incoming.receiveMessage<RealTimeC2S.ListenToSingleParticlePreprocessing>(outgoing)
				?: return

			// authenticate the user for this job
			val job = user.authJobOrThrow(ProjectPermission.Read, msg.jobId)

			// route messages to the client
			val eventListeners = (job as? MicrographsJob)
				?.eventListeners
				?: throw IllegalArgumentException("job ${job::class.simpleName} was not a MicrographsJob")
			eventListeners.add(job.idOrThrow)
				.apply {

					onParams = { values ->
						outgoing.trySendMessage(RealTimeS2C.UpdatedParameters(
							pypStats = PypStats.fromArgValues(values)
						))
					}

					onMicrograph = { micrograph ->
						outgoing.trySendMessage(RealTimeS2C.UpdatedMicrograph(micrograph.getMetadata()))
					}
				}
				.use {
					// wait for the connection to close, then cleanup the listeners
					incoming.waitForClose(outgoing)
				}
		}

		routing.webSocket(RealTimeServices.singleParticlePreprocessing) {
			micrographsService()
		}

		routing.webSocket(RealTimeServices.singleParticlePurePreprocessing) {
			micrographsService()
		}

		routing.webSocket(RealTimeServices.singleParticleDenoising) {
			micrographsService()
		}

		routing.webSocket(RealTimeServices.singleParticlePicking) {
			micrographsService()
		}

		suspend fun DefaultWebSocketServerSession.tiltSeriesesService() {

			val user = call.authOrThrow()

			// wait to hear which job the user wants
			val msg = incoming.receiveMessage<RealTimeC2S.ListenToTiltSerieses>(outgoing)
				?: return

			// authenticate the user for this job
			val job = user.authJobOrThrow(ProjectPermission.Read, msg.jobId)

			// route messages to the client via the listener
			val eventListeners = (job as? TiltSeriesesJob)
				?.eventListeners
				?: throw IllegalArgumentException("job ${job::class.simpleName} was not a TiltSeriesesJob")
			eventListeners.add(job.idOrThrow)
				.apply {

					onParams = { values ->
						outgoing.trySendMessage(RealTimeS2C.UpdatedParameters(
							pypStats = PypStats.fromArgValues(values)
						))
					}

					onTiltSeries = { tiltSeries ->
						outgoing.trySendMessage(RealTimeS2C.UpdatedTiltSeries(
							tiltSeries = tiltSeries.getMetadata()
						))
					}
				}
				.use {
					// wait for the connection to close, then cleanup the listeners
					incoming.waitForClose(outgoing)
				}
		}

		routing.webSocket(RealTimeServices.tomographyPreprocessing) {
			tiltSeriesesService()
		}

		routing.webSocket(RealTimeServices.tomographyPurePreprocessing) {
			tiltSeriesesService()
		}

		routing.webSocket(RealTimeServices.tomographyDenoisingEval) {
			tiltSeriesesService()
		}

		routing.webSocket(RealTimeServices.tomographyPicking) {
			tiltSeriesesService()
		}

		routing.webSocket(RealTimeServices.tomographySegmentationOpen) {
			tiltSeriesesService()
		}

		routing.webSocket(RealTimeServices.tomographySegmentationClosed) {
			tiltSeriesesService()
		}

		routing.webSocket(RealTimeServices.tomographyPickingOpen) {
			tiltSeriesesService()
		}

		routing.webSocket(RealTimeServices.tomographyPickingClosed) {
			tiltSeriesesService()
		}

		routing.webSocket(RealTimeServices.tomographyParticlesEval) {
			tiltSeriesesService()
		}

		routing.webSocket(RealTimeServices.tomographySessionData) {
			tiltSeriesesService()
		}

		routing.webSocket(RealTimeServices.reconstruction) handler@{

			val user = call.authOrThrow()

			// wait to hear which job the user wants
			val msg = incoming.receiveMessage<RealTimeC2S.ListenToReconstruction>(outgoing)
				?: return@handler

			// authenticate the user for this job
			val job = user.authJobOrThrow(ProjectPermission.Read, msg.jobId)

			// route messages to the client
			val listener = RefinementJobs.eventListeners.add(job.idOrThrow)
			listener.onParams = { values ->
				outgoing.trySendMessage(RealTimeS2C.UpdatedParameters(
					pypStats = PypStats.fromArgValues(values)
				))
			}
			listener.onReconstruction = { reconstruction ->
				outgoing.trySendMessage(RealTimeS2C.UpdatedReconstruction(reconstruction.toData()))
			}
			listener.onRefinement = { refinement ->
				outgoing.trySendMessage(RealTimeS2C.UpdatedRefinement(refinement.toData()))
			}
			listener.onRefinementBundle = { refinementBundle ->
				outgoing.trySendMessage(RealTimeS2C.UpdatedRefinementBundle(refinementBundle.toData()))
			}

			// wait for the connection to close, then cleanup the listeners
			incoming.waitForClose(outgoing)
			listener.close()
		}

		routing.webSocket(RealTimeServices.streamLog) handler@{

			val user = call.authOrThrow()

			// wait to hear which job the user wants
			var clusterJob: ClusterJob? = null
			when (val request = incoming.receiveMessage<RealTimeC2S>(outgoing)) {

				is RealTimeC2S.ListenToStreamLog -> {

					// authenticate the user for this cluster job
					clusterJob = user.authClusterJobOrThrow(ClusterJobPermission.Read, request.clusterJobId)
				}

				else -> Unit
			}
			if (clusterJob == null) {
				return@handler
			}

			// get all the current log entries from the stream log,
			// and the representative result, eg the first array job or the main job
			val representativeLog = clusterJob.getLog(if (clusterJob.commands.isArray) {
				1
			} else {
				null
			})
			outgoing.sendMessage(RealTimeS2C.StreamLogInit(
				messages = StreamLog.getAll(clusterJob.idOrThrow),
				resultType = representativeLog?.result?.type,
				exitCode = representativeLog?.result?.exitCode
			))

			// route log entries to the client
			val listener = StreamLog.addListener(clusterJob.idOrThrow)
			listener.onMsg = { msg ->
				outgoing.trySendMessage(RealTimeS2C.StreamLogMsgs(listOf(msg)))
			}
			listener.onEnd = { result ->
				outgoing.trySendMessage(RealTimeS2C.StreamLogEnd(result.type,  result.exitCode))
			}

			// wait for the connection to close, then cleanup the listeners
			incoming.waitForClose(outgoing)
			listener.close()
		}

		routing.webSocket(RealTimeServices.singleParticleSession) handler@{

			val user = call.authOrThrow()

			// wait to hear which session the user wants
			val request = incoming.receiveMessage<RealTimeC2S.ListenToSession>(outgoing)
				?: return@handler
			user.authSessionOrThrow(request.sessionId, SessionPermission.Read)

			// report the current session status
			val session = SingleParticleSession.fromIdOrThrow(request.sessionId)
			outgoing.sendSessionStatus(session)

			// send the large data (can take a while, so send it after the session status)
			outgoing.sendMessage(RealTimeS2C.SessionLargeData(
				autoParticlesCount = Database.instance.particles.countAllParticles(session.idOrThrow, ParticlesList.AutoParticles),
				micrographs = Micrograph.getAll(session.idOrThrow) { cursor ->
					cursor
						.map { it.getMetadata() }
						.toList()
				},
				twoDClasses = TwoDClasses.getAll(session.idOrThrow)
					.map { it.toData() }
			))

			// route all session events to the client
			val listener = makeSessionListener(session, outgoing)
			val filesystemListener = makeFilesystemListener(outgoing)
			val transfers = makeTransferListener(outgoing)

			// set up single-particle events
			listener.onParams = { values ->
				outgoing.trySendMessage(RealTimeS2C.UpdatedParameters(
					pypStats = PypStats.fromArgValues(values)
				))
			}
			listener.onMicrograph = listener@{ micrographId ->
				val micrograph = Micrograph.get(session.idOrThrow, micrographId) ?: return@listener
				outgoing.trySendMessage(RealTimeS2C.SessionMicrograph(micrograph.getMetadata()))
			}
			listener.onTwoDClasses = listener@{ twoDClasses ->
				outgoing.trySendMessage(RealTimeS2C.SessionTwoDClasses(twoDClasses.toData()))
			}
			listener.onExport = listener@{ export ->
				outgoing.trySendMessage(RealTimeS2C.SessionExport(export.toData()))
			}

			// start the transfer listener
			outgoing.trySendMessage(RealTimeS2C.SessionTransferInit(
				files = transfers.watchSession(session)
			))

			// wait for the connection to close, then cleanup the listeners
			try {
				incoming.waitForClose(outgoing) { msg ->
					when (msg) {

						// but if the session settings change, update the transfer listener
						is RealTimeC2S.SessionSettingsSaved -> {
							outgoing.trySendMessage(RealTimeS2C.SessionTransferInit(
								files = transfers.watchSession(session)
							))
						}

						// don't care about other messages
						else -> Unit
					}
				}
			} finally {

				// do all the cleanup steps, but independently
				// ie, if one step fails, still try to do the other steps
				attempt { Backend.instance.filesystems.listeners.remove(filesystemListener) }
				attempt { listener.close() }
				slowIOs {
					attempt { transfers.stopAndWait() }
				}
			}
		}

		routing.webSocket(RealTimeServices.tomographySession) handler@{

			val user = call.authOrThrow()

			// wait to hear which session the user wants
			val request = incoming.receiveMessage<RealTimeC2S.ListenToSession>(outgoing)
				?: return@handler
			user.authSessionOrThrow(request.sessionId, SessionPermission.Read)

			// report the current session status
			val session = TomographySession.fromIdOrThrow(request.sessionId)
			outgoing.sendSessionStatus(session)

			// send the large data (can take a while, so send it after the session status)
			outgoing.sendMessage(RealTimeS2C.SessionLargeData(
				autoVirionsCount = Database.instance.particles.countAllParticles(session.idOrThrow, ParticlesList.AutoVirions),
				autoParticlesCount = Database.instance.particles.countAllParticles(session.idOrThrow, ParticlesList.AutoParticles),
				tiltSerieses = TiltSeries.getAll(session.idOrThrow) { cursor ->
					cursor
						.map { it.getMetadata() }
						.toList()
				}
			))

			// route all session events to the client
			val listener = makeSessionListener(session, outgoing)
			val filesystemListener = makeFilesystemListener(outgoing)
			val transfers = makeTransferListener(outgoing)

			// set up tomography events
			listener.onParams = { values ->
				outgoing.trySendMessage(RealTimeS2C.UpdatedParameters(
					pypStats = PypStats.fromArgValues(values)
				))
			}
			listener.onTiltSeries = listener@{ tiltSeriesId ->
				val tiltSeries = TiltSeries.get(session.idOrThrow, tiltSeriesId) ?: return@listener
				outgoing.trySendMessage(RealTimeS2C.SessionTiltSeries(
					tiltSeries = tiltSeries.getMetadata()
				))
			}
			listener.onExport = listener@{ export ->
				outgoing.trySendMessage(RealTimeS2C.SessionExport(export.toData()))
			}

			// start the transfer listener
			outgoing.trySendMessage(RealTimeS2C.SessionTransferInit(
				files = transfers.watchSession(session)
			))

			// wait for the connection to close, then cleanup the listeners
			try {
				incoming.waitForClose(outgoing) { msg ->
					when (msg) {

						// but if the session settings change, update the transfer listener
						is RealTimeC2S.SessionSettingsSaved -> {
							outgoing.trySendMessage(RealTimeS2C.SessionTransferInit(
								files = transfers.watchSession(session)
							))
						}

						// don't care about other messages
						else -> Unit
					}
				}
			} finally {

				// do all the cleanup steps, but independently
				// ie, if one step fails, still try to do the other steps
				attempt { Backend.instance.filesystems.listeners.remove(filesystemListener) }
				attempt { listener.close() }
				slowIOs {
					attempt { transfers.stopAndWait() }
				}
			}
		}
	}

	private fun TransferWatcher.watchSession(session: Session): List<RealTimeS2C.SessionTransferInit.FileInfo> {

		val transferFolders = session.transferFolders()
			?: return emptyList()

		return watch(transferFolders.source, transferFolders.dest)
			.map { fileInfo ->
				RealTimeS2C.SessionTransferInit.FileInfo(
					fileInfo.path.fileName.toString(),
					fileInfo.bytesTotal,
					fileInfo.bytesTransfered
				)
			}
	}

	private suspend fun SendChannel<Frame>.sendSessionStatus(session: Session) {

		val values = session.pypParameters()
		val defaults = ArgValues(Backend.instance.pypArgsWithMicromon)

		// send the initial status
		// NOTE: this should be FAST so the UI feels responsive
		sendMessage(RealTimeS2C.SessionStatus(

			daemonsRunning = SessionDaemon.values()
				.map { daemon -> session.isRunning(daemon) },

			jobsRunning = session.runningJobs()
				.map { job ->
					RealTimeS2C.SessionRunningJob(
						jobId = job.idOrThrow,
						name = job.webName ?: "(unknown)",
						size = job.commands.numJobs,
						status = job.getLog()?.runStatus() ?: RunStatus.Waiting
					)
				},

			tomoVirMethod = (values ?: defaults).tomoVirMethodOrDefault,
			tomoVirRad = (values ?: defaults).tomoVirRadOrDefault,
			tomoVirBinn = (values ?: defaults).tomoVirBinnOrDefault,
			tomoVirDetectMethod = (values ?: defaults).tomoVirDetectMethodOrDefault,
			tomoSpkMethod = (values ?: defaults).tomoSpkMethodOrDefault,
			tomoSpkRad = (values ?: defaults).tomoSpkRadOrDefault
		))

		// send the small data
		sendMessage(RealTimeS2C.SessionSmallData(
			exports = SessionExport.getAll(session.idOrThrow)
				.map { it.toData() }
		))
	}

	private fun makeSessionListener(session: Session, outgoing: SendChannel<Frame>): SessionEvents.Listener {

		val listener = session.type.events.addListener(session.idOrThrow)

		listener.onDaemonSubmitted = { jobId, daemon ->
			outgoing.trySendMessage(RealTimeS2C.SessionDaemonSubmitted(jobId, daemon))
		}
		listener.onDaemonStarted = { jobId, daemon ->
			outgoing.trySendMessage(RealTimeS2C.SessionDaemonStarted(jobId, daemon))
		}
		listener.onDaemonFinished = { jobId, daemon ->
			outgoing.trySendMessage(RealTimeS2C.SessionDaemonFinished(jobId, daemon))
		}
		listener.onJobSubmitted = { jobId, name, size ->
			outgoing.trySendMessage(RealTimeS2C.SessionJobSubmitted(jobId, name, size))
		}
		listener.onJobStarted = { jobId ->
			outgoing.trySendMessage(RealTimeS2C.SessionJobStarted(jobId))
		}
		listener.onJobFinished = { jobId, resultType ->
			outgoing.trySendMessage(RealTimeS2C.SessionJobFinished(jobId, resultType))
		}

		return listener
	}

	private suspend fun makeFilesystemListener(outgoing: SendChannel<Frame>): Long {

		suspend fun List<DF.Filesystem>.send() {
			outgoing.sendMessage(RealTimeS2C.SessionFilesystems(
				filesystems = this
					.filter { fs ->
						// don't show temporary filesystems
						fs.type !in listOf("tmpfs", "devtmpfs", "overlay", "xfs") && fs.mountedOn.isDirectory()
					}
					.map { fs ->
						RealTimeS2C.SessionFilesystem(
							path = fs.mountedOn.toString(),
							type = fs.type,
							bytes = fs.bytes,
							bytesUsed = fs.bytesUsed
						)
					}
			))
		}

		// report the current filesystems
		Backend.instance.filesystems.filesystems().send()

		// route filesystem events to the client
		return Backend.instance.filesystems.listeners.add { filesystems ->
			filesystems.send()
		}
	}

	private suspend fun makeTransferListener(outgoing: SendChannel<Frame>): TransferWatcher {

		// TODO: should we filter out certain files?
		val transfers = TransferWatcher().apply {
			onWaiting = { path ->
				outgoing.trySendMessage(RealTimeS2C.SessionTransferWaiting(path.fileName.toString()))
			}
			onStarted = { path, bytesTotal ->
				outgoing.trySendMessage(RealTimeS2C.SessionTransferStarted(path.fileName.toString(), bytesTotal))
			}
			onProgress = { path, bytesTransferred ->
				outgoing.trySendMessage(RealTimeS2C.SessionTransferProgress(path.fileName.toString(), bytesTransferred))
			}
			onFinished = { path ->
				outgoing.trySendMessage(RealTimeS2C.SessionTransferFinished(path.fileName.toString()))
			}
		}

		return transfers
	}

	private fun attempt(block: () -> Unit) {
		try {
			block()
		} catch (t: Throwable) {
			Backend.log.error("Error cleaning up real-time connection", t)
		}
	}
}


 fun Route.webSocket(
	path: String,
	handler: suspend DefaultWebSocketServerSession.() -> Unit
) {
	webSocket(path, null) {
		try {
			handler()
		} catch (t: Throwable) {
			when (t) {

				// don't handle low-level IO exceptions, let ktor deal with them
				is CancellationException,
				is ChannelIOException -> throw t

				// translate exceptions we want the client to see into error messages and send them back
				is AuthException ->
					outgoing.trySendMessage(RealTimeS2C.Error(
						name = t::class.simpleName,
						msg = t.message
					))

				// send back external info only on these exceptions
				is AuthExceptionWithInternal -> {
					val e = t.external()
					outgoing.trySendMessage(RealTimeS2C.Error(
						name = e::class.simpleName,
						msg = e.message
					))
				}

				// hide everything else from the caller, these error details should stay internal
				else -> {
					outgoing.trySendMessage(RealTimeS2C.Error(
						name = "InternalError",
						msg = "See the server error log for more details"
					))

					// rethrow the error so it actually makes it into the server log
					throw t
				}
			}
		}
	}
}


fun Frame.toMessage(): RealTimeC2S {

	if (!fin) {
		throw Error("Frame is fragmented... shouldn't happen on a non-raw KTor websocket handler")
	}

	return when (this) {
		is Frame.Text -> RealTimeC2S.fromJson(data.toString(Charsets.UTF_8))
		else -> throw Error("not a text frame")
	}
}

/**
 * Waits for the next message and returns it.
 * Throws an excepion if the message has an unexpected type.
 * Returns null if the connection was closed.
 */
private suspend inline fun <reified M:RealTimeC2S> ReceiveChannel<Frame>.receiveMessage(outgoing: SendChannel<Frame>): M? {

	// listen to incoming messages
	for (frame in this) {

		// check for the expected message
		when (val msg = frame.toMessage()) {

			// but also respond to ping messages
			is RealTimeC2S.Ping -> outgoing.sendMessage(RealTimeS2C.Pong())

			// the message we wanted
			is M -> return msg

			// anything else is an error
			else -> throw UnexpectedMessageException(msg)
		}
	}

	// channel closed, no more messages
	return null
}

class UnexpectedMessageException(val msg: Any) : IllegalArgumentException("message type: ${msg::class.simpleName}")

private suspend fun ReceiveChannel<Frame>.waitForClose(outgoing: SendChannel<Frame>, messageHandler: suspend (RealTimeC2S) -> Unit = {}) {
	try {

		// listen for incoming messages until we can't anymore
		// respond to pings, but ignore the rest
		for (frame in this) {
			when (val msg = frame.toMessage()) {
				is RealTimeC2S.Ping -> outgoing.sendMessage(RealTimeS2C.Pong())
				else -> messageHandler(msg)
			}
		}

	} catch (ex: Throwable) {
		// ignore exceptions, usually they just mean the connection closed
		// which is what we wanted anyway
	}
}

private suspend inline fun <reified M:RealTimeS2C> SendChannel<Frame>.sendMessage(msg: M) {
	send(Frame.Text(msg.toJson()))
}

/** try to send the message, but don't throw an exception if no one's listening anymore */
private suspend inline fun <reified M:RealTimeS2C> SendChannel<Frame>.trySendMessage(msg: M) {
	try {
		// see if anyone's listening first
		if (!isClosedForSend) {
			sendMessage(msg)
		}
	} catch (ex: ClosedSendChannelException) {
		// user disconnected, ignore the exception

	} catch (ex: Throwable) {
		ex.printStackTrace(System.err)
	}
}
