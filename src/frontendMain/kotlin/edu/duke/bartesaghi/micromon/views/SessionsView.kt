package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.PathPopup
import edu.duke.bartesaghi.micromon.components.TabulatorProxy
import edu.duke.bartesaghi.micromon.components.forms.setSubmitButton
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.form.FormType
import io.kvision.form.formPanel
import io.kvision.form.select.Select
import io.kvision.form.text.Text
import io.kvision.html.*
import io.kvision.modal.Confirm
import io.kvision.modal.Modal
import io.kvision.navbar.navLink
import io.kvision.tabulator.*
import io.kvision.toast.Toast
import kotlin.js.Date


fun Widget.onGoToSessions() {
	onGo(SessionsView.path())
}


class SessionsView : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.register("^${path()}$") {
				viewport.setView(SessionsView())
			}
		}

		fun path() = "/sessions"

		fun go() {
			routing.go(path())
		}
	}

	override val elem = Div(classes = setOf("dock-page", "sessions"))

	private val deletingSessionIds = HashSet<String>()

	private var SessionData.isDeleting
		get() = sessionId in deletingSessionIds
		set(value) {
			if (value) {
				deletingSessionIds.add(sessionId)
			} else {
				deletingSessionIds.remove(sessionId)
			}
		}

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

		var sessions: List<SessionData> = ArrayList()
		val tabulator = TabulatorProxy<SessionData>()

		val typeSelect = Select(
			options = listOf(
				"" to "(All)",
				"spa" to "Single Particle",
				"tomo" to "Tomography"
			),
			value = "",
			label = "Type:"
		)

		val groupSelect = Select(
			label = "Group:"
			// NOTE: will load options later
		)

		// define some selectors for the SessionData
		fun SessionData.type(): String =
			when (this) {
				is SingleParticleSessionData -> "Single-particle"
				is TomographySessionData -> "Tomography"
			}
		fun SessionData.group(): String? =
			newestDisplay?.groupName
		fun SessionData.count(): Long =
			when (this) {
				is SingleParticleSessionData -> numMicrographs
				is TomographySessionData -> numTiltSeries
			}

		fun SessionData.framesOrTilts(): Long =
			when (this) {
				is SingleParticleSessionData -> numFrames
				is TomographySessionData -> numTilts
			}

		tabulator.tabulator = Tabulator.create(
			classes = setOf("table"),
			options = TabulatorOptions(
				layout = Layout.FITDATA,
				columns = listOf(
					ColumnDefinition(
						"",
						cssClass = "actionsColumn",
						formatterComponentFunction = tabulator.formatter { session -> Span().apply {

							if (session.isDeleting) {
								content = "Deleting ..."
								return@apply
							}

							val viewButton = button("", icon = "fas fa-eye").apply {
								title = if (SessionPermission.Write in session.permissions) {
									"Manage this Session"
								} else if(SessionPermission.Read in session.permissions) {
									"View this Session"
								} else {
									"Do nothing with this Session"
								}
							}
							if (listOf(SessionPermission.Write, SessionPermission.Read).any { it in session.permissions }) {
								viewButton.onClick {
									when (session) {
										is SingleParticleSessionData -> SingleParticleSessionView.go(viewport, session)
										is TomographySessionData -> TomographySessionView.go(viewport, session)
									}
								}
							}

							if (SessionPermission.Write in session.permissions) {

								button("", icon = "far fa-clone").apply {
									title = "Copy the settings for this session"
								}.onClick {
									copySession(viewport, session)
								}

								button("", icon = "fas fa-trash").apply {
									title = "Delete this Session"
								}.onClick {
									deleteSession(session, tabulator)
								}
							}

							if (SessionPermission.Read in session.permissions) {
								add(PathPopup.button("${session.numberedName} Filesystem Location", session.path))
							}
						}},
						headerSort = false,
						minWidth = 100
					),
					ColumnDefinition(
						"Type",
						formatterComponentFunction = tabulator.formatter { session ->
							Span {
								content = session.type()
							}
						},
						sorterFunction = tabulator.sorter { it.type() }
					),
					ColumnDefinition(
						"Name",
						formatterComponentFunction = tabulator.formatter { session ->
							Span {
								content = session.numberedName
							}
						},
						sorterFunction = tabulator.sorter { it.numberedName }
					),
					ColumnDefinition(
						"Group",
						formatterComponentFunction = tabulator.formatter { session ->
							Span {
								content = session.group() ?: "(no group assigned)"
							}
						},
						sorterFunction = tabulator.sorter { it.group() }
					),
					ColumnDefinition(
						"Micrographs/Tilt Series",
						formatterComponentFunction = tabulator.formatter { session ->
							Span {
								content = session.count().formatWithDigitGroupsSeparator().toString()
							}
						},
						sorterFunction = tabulator.sorter { it.count() }
					),
					ColumnDefinition(
						"Frames/Tilts",
						formatterComponentFunction = tabulator.formatter { session ->
							Span {
								content = session.framesOrTilts().formatWithDigitGroupsSeparator().toString()
							}
						},
						sorterFunction = tabulator.sorter { it.framesOrTilts() }
					),
					ColumnDefinition(
						"Created",
						formatterComponentFunction = tabulator.formatter { session ->
							Span {
								content = Date(session.created).toLocaleString()
							}
						},
						sorterFunction = tabulator.sorter { it.created }
					)
				),
				pagination = PaginationMode.LOCAL,
				paginationSize = 20,
				placeholder = "No sessions to show"
			)
		)

		fun updateTable() {
			tabulator.items = sessions
				.filter {
					val matchesType = typeSelect.value == null || typeSelect.value == "" || when (it) {
						is SingleParticleSessionData -> typeSelect.value == "spa"
						is TomographySessionData -> typeSelect.value == "tomo"
					}
					val matchesGroup = groupSelect.value == null || groupSelect.value == "" || groupSelect.value == it.newestArgs?.groupId
					matchesType && matchesGroup
				}
		}

		// wire up events
		typeSelect.onEvent {
			change = {
				updateTable()
			}
		}
		groupSelect.onEvent {
			change = {
				updateTable()
			}
		}

		// make buttons to start new sessions
		val startSpaButton = Button("Start Single-Particle", icon = "fas fa-plus").onClick {
			SingleParticleSessionView.go()
		}

		val startTomoButton = Button("Start Tomography", icon = "fas fa-plus").onClick {
			TomographySessionView.go()
		}

		// get the sessions
		AppScope.launch {

			var canStartSessions = false

			// load the sessions info
			val loadingElem = elem.loading("Fetching sessions ...")
			try {
				delayAtLeast(200) {
					groupSelect.options = Services.sessions.groups()
						.map { it.idOrThrow to it.name }
						.let { listOf("" to "(All)") + it }
					sessions = (Services.singleParticleSessions.list() + Services.tomographySessions.list())
						.sortedBy { it.created }
						.reversed()
					canStartSessions = Services.sessions.canStart()
				}
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loadingElem)
			}

			// layout the page
			elem.div {
				if (canStartSessions) {
					div(classes = setOf("start-buttons"))  {
						add(startSpaButton)
						add(startTomoButton)
					}
				}
				div(classes = setOf("filters")) {
					add(typeSelect)
					add(groupSelect)
				}
				add(tabulator.tabulatorOrThrow)
			}

			updateTable()

			// show the running sessions, if any
			val runningSessions = try {
				Services.sessions.running()
			} catch (t: Throwable) {
				Toast.error("failed to load running sessions: $t")
				null
			}
			if (runningSessions != null && (runningSessions.singleParticle.isNotEmpty() || runningSessions.tomography.isNotEmpty())) {
				elem.div(classes = setOf("running-sessions")) {
					h1("Currently running sessions")
					div(classes = setOf("sessions-list")) {
						(runningSessions.singleParticle + runningSessions.tomography)
							.sortedBy { it.created }
							.forEach { runningSession ->
								div(classes = setOf("sessions-entry")) {
									link(runningSession.numberedName).apply {
										when (runningSession) {
											is SingleParticleSessionData -> onGoToSingleParticleSession(viewport, runningSession)
											is TomographySessionData -> onGoToTomographySession(viewport, runningSession)
										}
									}
									div("Started at " + Date(runningSession.created).toLocaleString())
								}
							}
					}
				}
			}
		}
	}

	private fun deleteSession(session: SessionData, tabulator: TabulatorProxy<SessionData>) {

		// show a confirmation dialog before deleting
		val sessionName = session.numberedName
		val groupName = session.newestDisplay?.groupName ?: "(unknown)"
		Confirm.show(
			"Really delete this session?",
			"Are you sure you want to delete the session: \"$sessionName\" for $groupName?\n"
				+ "This action will delete all associated data from the database and the filesystem. "
				+ "It cannot be undone."
		) {

			fun TabulatorProxy<SessionData>.updateSession() {
				items
					.indexOfFirst { it === session }
					.takeIf { it >= 0 }
					?.let { tabulator.updateItem(it) }
			}

			// put the session into a deleting state
			session.isDeleting = true
			tabulator.updateSession()

			AppScope.launch {

				try {
					// delete on the server
					when (session) {
						is SingleParticleSessionData -> Services.singleParticleSessions.delete(session.sessionId)
						is TomographySessionData -> Services.tomographySessions.delete(session.sessionId)
					}
				} catch (t: Throwable) {

					// remove the deleting state
					session.isDeleting = false
					tabulator.updateSession()

					throw t
				}

				// finally, actually delete the session from the table
				tabulator.items = tabulator.items
					.filter { it !== session }
			}
		}
	}

	private fun copySession(viewport: Viewport, session: SessionData) {

		// show a popup to ask for a name for the copied session
		Modal(
			caption = "Copy session settings",
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup")
		).apply modal@{

			val form = formPanel<CopySessionArgs>(type = FormType.HORIZONTAL) {
				add(CopySessionArgs::name, Text(label = "Name"), required = true)
			}
			val errorsElem = div()
			val copyButton = button("Copy").onClick copy@{

				// make sure we have valid input
				if (!form.validate()) {
					return@copy
				}
				val args = form.getData()

				disabled = true

				AppScope.launch {

					// copy the session on the server
					val newSession = try {
						when (session) {
							is SingleParticleSessionData -> Services.singleParticleSessions.copy(session.sessionId, args)
							is TomographySessionData -> Services.tomographySessions.copy(session.sessionId, args)
						}
					} catch (t: Throwable) {
						errorsElem.removeAll()
						errorsElem.errorMessage(t)
						return@launch
					}

					this@modal.hide()

					// go to the new session
					when (newSession) {
						is SingleParticleSessionData -> SingleParticleSessionView.go(viewport, newSession)
						is TomographySessionData -> TomographySessionView.go(viewport, newSession)
					}
				}
			}

			form.setSubmitButton(copyButton)

			show()
		}
	}
}
