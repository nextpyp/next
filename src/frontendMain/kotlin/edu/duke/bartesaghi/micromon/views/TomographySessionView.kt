package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.form.formPanel
import io.kvision.form.select.SelectRemote
import io.kvision.form.text.Text
import io.kvision.html.*
import io.kvision.navbar.navLink
import io.kvision.toast.Toast
import kotlinx.browser.document
import kotlinx.html.dom.create
import kotlinx.html.js.img
import kotlin.js.Date


fun Widget.onGoToNewTomographySession() {
	onGo(TomographySessionView.path())
}

fun Widget.onGoToTomographySession(viewport: Viewport, session: TomographySessionData) {
	onShow(TomographySessionView.path(session.sessionId)) {
		viewport.setView(TomographySessionView(session))
	}
}

class TomographySessionView(
	var session: TomographySessionData? = null
) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.register("^${path()}$") {
				viewport.setView(TomographySessionView())
			}
			routing.registerParams("^${path()}/($urlToken)$") { sessionId ->
				AppScope.launch {
					try {
						val session = Services.tomographySessions.get(sessionId)
						viewport.setView(TomographySessionView(session))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(sessionId: String? = null) =
			if (sessionId != null) {
				"/session/tomography/$sessionId"
			} else {
				"/session/tomography"
			}

		fun go(sessionId: String? = null) {
			routing.go(path(sessionId))
		}

		fun go(viewport: Viewport, session: TomographySessionData) {
			routing.show(path(session.sessionId))
			viewport.setView(TomographySessionView(session))
		}

		val pypArgs = ServerVal {
			Args.fromJson(Services.tomographySessions.getArgs())
		}
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "session"))

	private var viewport: Viewport? = null
	private val statsElem = Div(classes = setOf("stats"))
	private val tiltSeriesStats = TiltSeriesStats()
	private val header = Div(classes = setOf("header"))
	private val tabs = LazyTabPanel()
	private var settingsTab: SettingsTab? = null
	private var opsTab: SessionOps? = null
	private var plotsTab: PlotsTab? = null
	private var tiltSeriesTab: TiltSeriesTab? = null
	private var tiltSeriesTabId: Int? = null
	private var tableTab: TableTab? = null
	private var galleryTab: GalleryTab? = null
	private var connector: WebsocketConnector? = null
	private val tiltSeriesesData = TiltSeriesesData()
	private var sendSettingsSaved: (suspend () -> Unit)? = null


	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink("Sessions", icon = "fas fa-server")
					.onGoToSessions()
			}
		}

		this.viewport = viewport

		// layout the view
		elem.add(header)
		elem.add(statsElem)
		elem.add(tabs)

		updateStatistics()

		// add the always-on tabs
		settingsTab = SettingsTab()
		tabs.addTab("Settings", "fas fa-sliders-h") {
			settingsTab?.show(it.elem)
		}
		tabs.initWithDefaultTab()

		val session = session
		if (session == null) {
			initNewSession()
		} else {
			initSession(session)
		}
	}

	private fun initNewSession() {

		// add the link to the nav bar
		val viewport = viewport ?: return
		val nav = viewport.navbarElem.nav ?: return
		nav.navLink("New Tomography Session", icon = "fas fa-plus", classes = setOf("new-link"))
			.onGoToNewTomographySession()
	}

	private fun initSession(session: TomographySessionData) {

		// add the session link to the nav bar
		val viewport = viewport ?: return
		val nav = viewport.navbarElem.nav ?: return
		nav.navLink(session.numberedName, icon = "fas fa-microscope")
			.onGoToTomographySession(viewport, session)

		// add the extra tabs
		opsTab = SessionOps(session)
		val opsTabId = tabs.addTab("Operation", "fas fa-cogs") {
			opsTab?.show(it.elem)
		}.id

		plotsTab = PlotsTab(session)
		tabs.addTab("Plots", "far fa-chart-bar") {
			plotsTab?.show(it)
		}

		tableTab = TableTab(session)
		tabs.addTab("Table", "fas fa-table") {
			tableTab?.show(it)
		}

		galleryTab = GalleryTab(session)
		tabs.addTab("Gallery", "fas fa-image") {
			galleryTab?.show(it)
		}

		tiltSeriesTab = TiltSeriesTab(session)
		tiltSeriesTabId = tabs.addTab("Tilt Series", "fas fa-desktop") {
			tiltSeriesTab?.show(it)
		}.id

		// start on the ops tab
		tabs.showTab(opsTabId)

		// open the websocket connection to listen for server-side updates
		val connector = WebsocketConnector(RealTimeServices.tomographySession) { signaler, input, output ->

			// tell the server we want to listen to this session
			output.send(RealTimeC2S.ListenToSession(session.sessionId).toJson())

			signaler.connected()

			sendSettingsSaved = {
				output.send(RealTimeC2S.SessionSettingsSaved().toJson())
			}

			// wait for the init message from the server
			val initMsg = input.receiveMessage<RealTimeS2C.SessionStatus>()
			try {
				opsTab?.init(initMsg)
			} catch (t: Throwable) {
				t.reportError()
				Toast.error("An error occurred while loading the session status.")
			}

			// wait for the small data message from the server
			val smallDataMsg = input.receiveMessage<RealTimeS2C.SessionSmallData>()
			opsTab?.exportsMonitor?.setData(smallDataMsg)

			// wait for the large data message from the server
			val dataMsg = input.receiveMessage<RealTimeS2C.SessionLargeData>()
			try {
				tiltSeriesesData.loadForSession(session, initMsg, dataMsg)
				tiltSeriesStats.set(
					tiltSeriesesData,
					virions = dataMsg.autoVirionsCount,
					particles = dataMsg.autoParticlesCount
				)
				updateStatistics()
				plotsTab?.loader?.setData(dataMsg)
				tableTab?.loader?.setData(dataMsg)
				galleryTab?.loader?.setData(dataMsg)
				tiltSeriesTab?.loader?.setData(dataMsg)
			} catch (t: Throwable) {
				t.reportError()
				Toast.error("An error occurred while loading the large session data.")
			}

			// wait for other updates from the server
			for (msgstr in input) {

				// run the message handler in a coroutine, so it doesn't block/abort the message loop
				AppScope.launch {
					try {
						when (val msg = RealTimeS2C.fromJson(msgstr)) {
							is RealTimeS2C.SessionDaemonSubmitted -> opsTab?.daemonSubmitted(msg)
							is RealTimeS2C.SessionDaemonStarted -> opsTab?.daemonStarted(msg)
							is RealTimeS2C.SessionDaemonFinished -> opsTab?.daemonFinished(msg)
							is RealTimeS2C.SessionJobSubmitted -> opsTab?.jobsMonitor?.submitted(msg)
							is RealTimeS2C.SessionJobStarted -> opsTab?.jobsMonitor?.started(msg)
							is RealTimeS2C.SessionJobFinished -> opsTab?.jobsMonitor?.finished(msg)
							is RealTimeS2C.SessionFilesystems -> opsTab?.filesystemMonitor?.update(msg)
							is RealTimeS2C.SessionTransferInit -> opsTab?.transferMonitor?.init(msg)
							is RealTimeS2C.SessionTransferWaiting -> opsTab?.transferMonitor?.waiting(msg)
							is RealTimeS2C.SessionTransferStarted -> opsTab?.transferMonitor?.started(msg)
							is RealTimeS2C.SessionTransferProgress -> opsTab?.transferMonitor?.progress(msg)
							is RealTimeS2C.SessionTransferFinished -> {
								opsTab?.transferMonitor?.finished(msg)
								opsTab?.speedsMonitor?.transferFinished()
							}
							is RealTimeS2C.UpdatedParameters -> {
								tiltSeriesTab?.listNav?.reshow()
							}
							is RealTimeS2C.SessionTiltSeries -> {
								tiltSeriesesData.update(msg.tiltSeries)
								tiltSeriesStats.increment(tiltSeriesesData, msg.tiltSeries)
								updateStatistics()
								tiltSeriesTab?.listNav?.newItem()
								plotsTab?.onTiltSeries(msg)
								tableTab?.onTiltSeries()
								galleryTab?.onTiltSeries()
							}
							is RealTimeS2C.SessionExport -> {
								opsTab?.exportsMonitor?.update(msg)
							}
							else -> Unit
						}
					} catch (t: Throwable) {
						t.reportError()
						Toast.error("An error occurred processing messages from the server.")
					}
				}
			}
		}
		this.connector = connector
		header.add(WebsocketControl(connector))
		connector.connect()
	}

	private fun showTiltSeries(index: Int, stopLive: Boolean) {

		// make sure the tilt series tab is showing
		tiltSeriesTabId?.let {
			tabs.showTab(it)
		}

		tiltSeriesTab?.listNav?.showItem(index, stopLive)
	}

	private inner class SettingsTab {

		fun show(elem: Container) {

			elem.addCssClass("settings")

			val newSession = session == null
			val writeable = session
				?.let { SessionPermission.Write in it.permissions }
				?: true

			AppScope.launch {

				val argsConfig = pypArgs.get()

				val nameText = Text(
					label = "Name"
				).apply {
					disabled = !writeable
				}

				val folderChooser = CreateFolderChooser()
					.control("Folder")
					.apply {
						enabled = writeable
							// The folder is only writable when creating a new session.
							// It can't be changed after the session is created
							&& session == null
					}

				val groupSelect = SelectRemote(
					serviceManager = SessionsServiceManager,
					function = ISessionsService::groupOptions,
					label = "Group"
				).apply {
					disabled = !writeable
					// HACKHACK: fix defaults on remote select controls
					lookupDefault { groupId ->
						Services.sessions.groupOptions(null, groupId, null)
					}
				}

				val extraTab = ArgsForm.ExtraTab(
					"Session",
					ArgsForm.ExtraTab.Position.Before,
					classes = setOf("sub-form", "grid", "mainTab")
				) {
					add(nameText)
					add(folderChooser)
					add(groupSelect)
				}

				val form = elem.formPanel<TomographySessionArgs> {
					add(TomographySessionArgs::name, nameText.proxy(), required = true)
					add(TomographySessionArgs::path, folderChooser.proxy(), required = true)
					add(TomographySessionArgs::groupId, groupSelect.proxy(), required = true)
					add(TomographySessionArgs::values, ArgsForm(argsConfig, enabled = writeable, extraTabs = listOf(extraTab)))
				}

				form.init(session?.args)

				if (newSession) {

					// generate form defaults
					nameText.value = "New Session"
					folderChooser.value = Services.sessions.pickFolder()
				}

				if (writeable) {
					form.addSaveResetButtons(session?.args) { args ->

						// save the changes
						val session = this@TomographySessionView.session
						if (session == null) {
							createSession(args)
						} else {
							saveSession(session, args)
						}
					}
				}
			}
		}

		private fun createSession(args: TomographySessionArgs) {

			AppScope.launch {

				val session = try {
					Services.tomographySessions.create(args)
				} catch (t: Throwable) {
					Toast.error(t.message ?: "(Unknown error)")
					t.reportError()
					return@launch
				}

				// remove the new session link from the nav
				viewport?.navbarElem?.nav?.let { nav ->
					nav.getChildren().lastOrNull()?.let { nav.remove(it) }
				}

				// update the route
				routing.show(path(session.sessionId))

				initSession(session)

				this@TomographySessionView.session = session
				updateStatistics()
			}
		}

		private fun saveSession(session: TomographySessionData, args: TomographySessionArgs) {

			AppScope.launch {

				try {
					Services.tomographySessions.edit(session.sessionId, args)
				} catch (t: Throwable) {
					Toast.error(t.message ?: "(Unknown error)")
					t.reportError()
					return@launch
				}

				// tell the real-time service we saved the session
				this@TomographySessionView.sendSettingsSaved?.invoke()

				this@TomographySessionView.session!!.args.next = args
				updateStatistics()

				Toast.success("Session saved")
			}
		}
	}

	private inner class PlotsTab(val session: SessionData) : Div() {

		val loader = TabDataLoader<RealTimeS2C.SessionLargeData>()

		private val plots = PreprocessingPlots(
			tiltSeriesesData.tiltSerieses,
			goto = { _, index ->
				showTiltSeries(index, true)
			},
			tooltipImageUrl = { it.imageUrl(session, ImageSize.Small) },
		)

		init {
			// start with a loading message
			loading("Loading tilt series ...", classes = setOf("spaced"))

			// wire up events
			loader.onData = {
				removeAll()
				add(plots)
				plots.load()
			}
		}

		fun show(lazyTab: LazyTabPanel.LazyTab) {
			loader.init(lazyTab)
			lazyTab.elem.add(this)
		}

		fun onTiltSeries(msg: RealTimeS2C.SessionTiltSeries) {
			plots.update(msg.tiltSeries)
		}
	}

	private inner class TableTab(val session: SessionData) : Div() {

		val loader = TabDataLoader<RealTimeS2C.SessionLargeData>()

		private val table = FilterTable(
			"Tilt Series",
			tiltSeriesesData.tiltSerieses,
			TiltSeriesProp.values().toList(),
			writable = SessionPermission.Write in session.permissions,
			showDetail = { elem, index, tiltSeries ->

				// show the micrograph stuff
				elem.div {
					link(tiltSeries.id, classes = setOf("link"))
						.onClick { showTiltSeries(index, true) }
				}
				elem.add(SessionTiltSeriesImage(session, tiltSeries).apply {
					loadParticles()
				})
				elem.add(SessionTiltSeries1DPlot(session, tiltSeries).apply {
					loadData()
				})
				elem.add(SessionTiltSeries2DPlot(session, tiltSeries))
			},
			filterer = FilterTable.Filterer(
				save = { filter -> Services.sessions.saveFilter(session.sessionId, filter) },
				delete = { name -> Services.sessions.deleteFilter(session.sessionId, name) },
				names = { Services.sessions.listFilters(session.sessionId) },
				get = { name -> Services.sessions.getFilter(session.sessionId, name) },
				export = { name -> opsTab?.export(SessionExportRequest.Filter(name)) }
			)
		)

		init {
			// start with a loading message
			loading("Loading tilt series ...", classes = setOf("spaced"))

			// wire up events
			loader.onData = {
				removeAll()
				add(table)
				table.load()
			}
		}

		fun show(lazyTab: LazyTabPanel.LazyTab) {
			loader.init(lazyTab)
			lazyTab.elem.add(this)
		}

		fun onTiltSeries() {
			table.update()
		}
	}

	private inner class GalleryTab(val session: SessionData) : Div() {

		val loader = TabDataLoader<RealTimeS2C.SessionLargeData>()

		private val gallery = HyperGallery(tiltSeriesesData.tiltSerieses, ImageSizes.from(ImageSize.Small)).apply {
			html = { tiltSeries ->
				listenToImageSize(document.create.img(src = tiltSeries.imageUrl(this@GalleryTab.session, ImageSize.Small)))
			}
			linker = { _, index ->
				showTiltSeries(index, true)
			}
		}

		init {
			// start with a loading message
			loading("Loading tilt series ...", classes = setOf("spaced"))

			// wire up events
			loader.onData = {
				removeAll()
				add(gallery)
				gallery.update()
			}
		}

		fun show(lazyTab: LazyTabPanel.LazyTab) {
			lazyTab.elem.add(this)
			loader.init(lazyTab)
		}

		fun onTiltSeries() {
			gallery.update()
		}
	}

	private inner class TiltSeriesTab(val session: SessionData) : Div(classes = setOf("live")) {

		val loader = TabDataLoader<RealTimeS2C.SessionLargeData>()

		private val tiltSeriesElem = Div()

		val listNav = BigListNav(tiltSeriesesData.tiltSerieses, onSearch=tiltSeriesesData::searchById) e@{ index ->

			// clear the previous contents
			tiltSeriesElem.removeAll()

			// get the indexed tilt series, if any
			val tiltSeries = tiltSeriesesData.tiltSerieses.getOrNull(index)
			if (tiltSeries == null) {
				tiltSeriesElem.div("No tilt series to show", classes = setOf("empty"))
				return@e
			}

			val session = session

			// show metadata
			tiltSeriesElem.div(classes = setOf("stats")) {
				div {
					span("Name: ${tiltSeries.id}, processed on ${Date(tiltSeries.timestamp).toLocaleString()}")
					button("Show Log", classes = setOf("log-button")).onClick {
						LogView.showPopup("Log for Tilt Series: ${tiltSeries.id}") {
							Services.sessions.dataLog(session.sessionId, tiltSeries.id)
						}
					}
				}
			}

			// show the tomogaphy sub-tabs
			val tomoPanel = SessionTomoMultiPanel(session, tiltSeriesesData, tiltSeries)
			tiltSeriesElem.add(tomoPanel)
			AppScope.launch {
				tomoPanel.load()
			}
		}

		init {
			// start with a loading message
			loading("Loading tilt series ...", classes = setOf("spaced"))

			// wire up events
			loader.onData = {
				removeAll()
				add(listNav)
				add(tiltSeriesElem)

				// start with the newest tilt series
				listNav.showItem(tiltSeriesesData.tiltSerieses.size - 1, false)
			}
		}

		fun show(lazyTab: LazyTabPanel.LazyTab) {
			lazyTab.elem.add(this)
			loader.init(lazyTab)
		}
	}

	override fun close() {

		// cleanup the websocket connection
		connector?.disconnect()
		sendSettingsSaved = null
	}

	private fun updateStatistics() {

		AppScope.launch {

			val args = pypArgs.get()
			val argsValues = session?.newestArgs?.values?.toArgValues(args)

			statsElem.removeAll()
			statsElem.add(tiltSeriesStats)
			statsElem.add(PypStatsLine(PypStats.fromArgValues(argsValues)))
		}
	}
}
