package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.components.forms.ArgsForm
import edu.duke.bartesaghi.micromon.components.forms.addSaveResetButtons
import edu.duke.bartesaghi.micromon.components.forms.init
import edu.duke.bartesaghi.micromon.components.forms.lookupDefault
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


fun Widget.onGoToNewSingleParticleSession() {
	onGo(SingleParticleSessionView.path())
}

fun Widget.onGoToSingleParticleSession(viewport: Viewport, session: SingleParticleSessionData) {
	onShow(SingleParticleSessionView.path(session.sessionId)) {
		viewport.setView(SingleParticleSessionView(session))
	}
}

class SingleParticleSessionView(
	var session: SingleParticleSessionData? = null
) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.register("^${path()}$") {
				viewport.setView(SingleParticleSessionView())
			}
			routing.registerParams("^${path()}/($urlToken)$") { sessionId ->
				AppScope.launch {
					try {
						val session = Services.singleParticleSessions.get(sessionId)
						viewport.setView(SingleParticleSessionView(session))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(sessionId: String? = null) =
			if (sessionId != null) {
				"/session/singleParticle/$sessionId"
			} else {
				"/session/singleParticle"
			}

		fun go(sessionId: String? = null) {
			routing.go(path(sessionId))
		}

		fun go(viewport: Viewport, session: SingleParticleSessionData) {
			routing.show(path(session.sessionId))
			viewport.setView(SingleParticleSessionView(session))
		}

		val pypArgs = ServerVal {
			Args.fromJson(Services.singleParticleSessions.getArgs())
		}
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "session"))

	private var viewport: Viewport? = null
	private val statsElem = Div(classes = setOf("stats"))
	private val header = Div(classes = setOf("header"))
	private val tabs = LazyTabPanel()
	private var settingsTab: SettingsTab? = null
	private var opsTab: SessionOps? = null
	private var plotsTab: PlotsTab? = null
	private var micrographsTab: MicrographsTab? = null
	private var micrographsTabId: Int? = null
	private var tableTab: TableTab? = null
	private var galleryTab: GalleryTab? = null
	private var connector: WebsocketConnector? = null
	private val micrographs = ArrayList<MicrographMetadata>()
	private var twoDClassesTab: TwoDClassesTab? = null
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
		nav.navLink("New Single-Particle Session", icon = "fas fa-plus", classes = setOf("new-link"))
			.onGoToNewSingleParticleSession()
	}

	private fun initSession(session: SingleParticleSessionData) {

		// add the session link to the nav bar
		val viewport = viewport ?: return
		val nav = viewport.navbarElem.nav ?: return
		nav.navLink(session.numberedName, icon = "fas fa-microscope")
			.onGoToSingleParticleSession(viewport, session)

		// add the extra tabs
		opsTab = SessionOps(session).apply {
			onClear = { daemon ->
				if (daemon == SessionDaemon.Fypd) {
					twoDClassesTab?.clearTwoDClasses()
				}
			}
		}
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

		micrographsTab = MicrographsTab(session)
		micrographsTabId = tabs.addTab("Micrographs", "fas fa-desktop") {
			micrographsTab?.show(it)
		}.id

		twoDClassesTab = TwoDClassesTab()
		tabs.addTab("2D Classes", "fas fa-th-large") {
			twoDClassesTab?.show(it)
		}

		// start on the ops tab
		tabs.showTab(opsTabId)

		// open the websocket connection to listen for server-side updates
		val connector = WebsocketConnector(RealTimeServices.singleParticleSession) { signaler, input, output ->

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
			try {
			} catch (t: Throwable) {
				t.reportError()
				Toast.error("An error occurred while loading the session small data.")
			}

			// wait for the large data message from the server
			val largeDataMsg = input.receiveMessage<RealTimeS2C.SessionLargeData>()
			try {
				micrographs.addAll(largeDataMsg.micrographs)
				updateStatistics()
				plotsTab?.loader?.setData(largeDataMsg)
				tableTab?.loader?.setData(largeDataMsg)
				galleryTab?.loader?.setData(largeDataMsg)
				micrographsTab?.loader?.setData(largeDataMsg)
				twoDClassesTab?.loader?.setData(largeDataMsg)
			} catch (t: Throwable) {
				t.reportError()
				Toast.error("An error occurred while loading the session large data.")
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
								micrographsTab?.update(msg)
							}
							is RealTimeS2C.SessionMicrograph -> {

								// add or update the micrograph
								val index = micrographs.indexOfFirst { it.id == msg.micrograph.id }
								if (index >= 0) {
									micrographs[index] = msg.micrograph
								} else {
									micrographs.add(msg.micrograph)
								}

								updateStatistics()
								micrographsTab?.listNav?.newItem()
								plotsTab?.onMicrograph(msg)
								tableTab?.onMicrograph()
								galleryTab?.onMicrograph()
							}
							is RealTimeS2C.SessionTwoDClasses -> {
								twoDClassesTab?.onTwoDClasses(msg)
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

	private fun showMicrograph(index: Int, stopLive: Boolean) {

		// make sure the micrographs tab is showing
		micrographsTabId?.let {
			tabs.showTab(it)
		}

		micrographsTab?.listNav?.showItem(index, stopLive)
	}

	private inner class SettingsTab {

		fun show(elem: Container) {

			elem.addCssClass("settings")

			val writeable = session
				?.let { SessionPermission.Write in it.permissions }
				?: true

			AppScope.launch {

				val argsConfig = pypArgs.get()

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

				val form = elem.formPanel<SingleParticleSessionArgs> {

					add(SingleParticleSessionArgs::name, Text(
						label = "Session Name",
						value = "New Session"
					).apply {
						disabled = !writeable
					}, required = true)

					add(SingleParticleSessionArgs::groupId, groupSelect, required = true)

					add(SingleParticleSessionArgs::values, ArgsForm(argsConfig, enabled = writeable))
				}

				form.init(session?.args)
				if (writeable) {
					form.addSaveResetButtons(session?.args) { args ->

						// save the changes
						val session = this@SingleParticleSessionView.session
						if (session == null) {
							createSession(args)
						} else {
							saveSession(session, args)
						}
					}
				}
			}
		}

		private fun createSession(args: SingleParticleSessionArgs) {

			AppScope.launch {

				val session = try {
					Services.singleParticleSessions.create(args)
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

				this@SingleParticleSessionView.session = session
				updateStatistics()
			}
		}

		private fun saveSession(session: SingleParticleSessionData, args: SingleParticleSessionArgs) {

			AppScope.launch {

				try {
					Services.singleParticleSessions.edit(session.sessionId, args)
				} catch (t: Throwable) {
					Toast.error(t.message ?: "(Unknown error)")
					t.reportError()
					return@launch
				}

				// tell the real-time service we saved the session
				this@SingleParticleSessionView.sendSettingsSaved?.invoke()

				this@SingleParticleSessionView.session!!.args.next = args
				updateStatistics()

				Toast.success("Session saved")
			}
		}
	}

	private inner class PlotsTab(val session: SessionData) : Div() {

		val loader = TabDataLoader<RealTimeS2C.SessionLargeData>()

		private val plots = PreprocessingPlots(
			micrographs,
			goto = { _, index ->
				showMicrograph(index, true)
			},
			tooltipImageUrl = { it.imageUrl(session, ImageSize.Small) }
		)

		init {
			// start with a loading message
			loading("Loading micrographs ...", classes = setOf("spaced"))

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

		fun onMicrograph(msg: RealTimeS2C.SessionMicrograph) {
			plots.update(msg.micrograph)
		}
	}

	private inner class TableTab(val session: SessionData) : Div() {

		val loader = TabDataLoader<RealTimeS2C.SessionLargeData>()

		private val table = FilterTable(
			"Micrograph",
			micrographs,
			MicrographProp.values().toList(),
			writable = SessionPermission.Write in session.permissions,
			showDetail = { elem, index, micrograph ->

				// show the micrograph stuff
				elem.div {
					link(micrograph.id, classes = setOf("link"))
						.onClick { showMicrograph(index, true) }
				}
				elem.add(SessionMicrographImage(session, micrograph).apply {
					loadParticles()
				})
				elem.add(SessionMicrograph1DPlot(session, micrograph).apply {
					loadData()
				})
				elem.add(SessionMicrograph2DPlot(session, micrograph))
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
			loading("Loading micrographs ...", classes = setOf("spaced"))

			// wire up events
			loader.onData = {
				removeAll()
				add(table)
				table.load()
			}
		}

		fun show(lazyTab: LazyTabPanel.LazyTab) {
			lazyTab.elem.add(this)
			loader.init(lazyTab)
		}

		fun onMicrograph() {
			table.update()
		}
	}

	private inner class GalleryTab(val session: SessionData) : Div() {

		val loader = TabDataLoader<RealTimeS2C.SessionLargeData>()

		private val gallery = HyperGallery(micrographs, ImageSizes.from(ImageSize.Small)).apply {
			html = { micrograph ->
				listenToImageSize(document.create.img(src = micrograph.imageUrl(this@GalleryTab.session, ImageSize.Small)))
			}
			linker = { _, index ->
				showMicrograph(index, true)
			}
		}

		init {
			// start with a loading message
			loading("Loading micrographs ...", classes = setOf("spaced"))

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

		fun onMicrograph() {
			gallery.update()
		}
	}

	private inner class MicrographsTab(val session: SessionData) : Div(classes = setOf("live")) {

		val loader = TabDataLoader<RealTimeS2C.SessionLargeData>()

		private val micrographElem = Div()

		val listNav = BigListNav(micrographs, onSearch=micrographs::searchById) e@{ index ->

			// clear the previous contents
			micrographElem.removeAll()

			// get the indexed micrograph, if any
			val micrograph = micrographs.getOrNull(index)
			if (micrograph == null) {
				micrographElem.div("No micrographs to show", classes = setOf("empty"))
				return@e
			}

			val session = session

			// show metadata
			micrographElem.div(classes = setOf("stats")) {
				div {
					span("Name: ${micrograph.id}, processed on ${Date(micrograph.timestamp).toLocaleString()}")
					button("Show Log", classes = setOf("log-button")).onClick {
						LogView.showPopup("Log for Micrograph: ${micrograph.id}") {
							Services.sessions.dataLog(session.sessionId, micrograph.id)
						}
					}
				}
			}

			// show the micrograph image
			micrographElem.add(SessionMicrographImage(session, micrograph).apply {
				loadParticles()
			})

			// show the motion
			micrographElem.add(SessionMicrographMotionPlot(session, micrograph).apply {
				loadData()
			})

			// show the 1D CTF profile
			micrographElem.add(SessionMicrograph1DPlot(session, micrograph).apply {
				loadData()
			})

			// show the 2D CTF profile
			micrographElem.add(SessionMicrograph2DPlot(session, micrograph))
		}

		init {
			// start with a loading message
			loading("Loading micrographs ...", classes = setOf("spaced"))

			// wire up events
			loader.onData = {
				removeAll()
				add(listNav)
				add(micrographElem)

				// start with the newest micrograph
				listNav.showItem(micrographs.size - 1, false)
			}
		}

		fun show(lazyTab: LazyTabPanel.LazyTab) {
			lazyTab.elem.add(this)
			loader.init(lazyTab)
		}

		fun update(msg: RealTimeS2C.UpdatedParameters) {
			listNav.reshow()
		}
	}

	private inner class TwoDClassesTab : Div(classes = setOf("twod-classes")) {

		val loader = TabDataLoader<RealTimeS2C.SessionLargeData>()

		private val list = ArrayList<TwoDClassesData>()
		private val descriptionElem = Div(classes = setOf("description"))
		private val imagePanel = SizedPanel(
			title = "2D Classes",
			initialSize = Storage.twodClassesImageSize
		).apply {
			onResize = { imageSize ->
				Storage.twodClassesImageSize = imageSize
				this@TwoDClassesTab.update()
			}
		}
		private val panelElem = Div()

		private val nav = BigListNav(
			items = list,
			has100 = false,
			onShow = { update() }
		)

		init {

			// start with a loading message
			loading("Loading 2D Classes ...", classes = setOf("spaced"))

			// wire up events
			loader.onData = { msg ->

				removeAll()

				// because kotlin DSLs are dumb sometimes
				val me = this

				// build the UI
				div(classes = setOf("controls")) {
					add(me.nav)
					add(me.descriptionElem)
				}
				add(imagePanel)
				imagePanel.add(panelElem)

				// update the list nav
				list.clear()
				list.addAll(msg.twoDClasses.sortedBy { it.created })
				nav.newItem()
			}
		}

		fun show(lazyTab: LazyTabPanel.LazyTab) {
			lazyTab.elem.add(this)
			loader.init(lazyTab)
		}

		fun onTwoDClasses(msg: RealTimeS2C.SessionTwoDClasses) {
			list.add(msg.twoDClasses)
			nav.newItem()
		}

		private fun update() {

			panelElem.removeAll()
			descriptionElem.content = ""

			if (list.isEmpty()) {
				panelElem.div("(No 2D classes to show here)", classes = setOf("empty"))
				return
			}

			val currentIndex = nav.currentIndex
				?: run {
					panelElem.div("(no iteration selected)", classes = setOf("empty"))
					return
				}
			val twoDClasses = list.getOrNull(currentIndex)
				?: run {
					panelElem.div("(selected 2D classes not found)", classes = setOf("empty"))
					return
				}

			descriptionElem.content = "Iteration ${currentIndex + 1} at ${Date(twoDClasses.created).toLocaleString()}"
			panelElem.image(twoDClasses.imageUrlSession(imagePanel.size))
		}

		fun clearTwoDClasses() {
			list.clear()
			nav.cleared()
			update()
		}
	}

	override fun close() {

		// cleanup the websocket connection
		connector?.disconnect()
		sendSettingsSaved = null
	}

	private fun updateStatistics() {

		val numMicrographs = micrographs.size
		val numParticles = micrographs.sumOf { it.numAutoParticles }

		AppScope.launch {

			val args = pypArgs.get()
			val argsValues = session?.newestArgs?.values?.toArgValues(args)

			statsElem.removeAll()
			statsElem.div {
				content = listOf(
					"Total: ${numMicrographs.formatWithDigitGroupsSeparator()} micrograph(s)",
					"${numParticles.formatWithDigitGroupsSeparator()} particle(s)",
					"Radius: ${argsValues?.detectRad ?: "(unknown)"} A"
				).joinToString(", ")
			}
			statsElem.add(PypStatsLine(PypStats.fromArgValues(argsValues)))
		}
	}
}
