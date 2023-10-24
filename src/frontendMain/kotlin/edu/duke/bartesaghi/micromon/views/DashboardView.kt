package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.components.forms.enableClickIf
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.components.forms.setSubmitButton
import edu.duke.bartesaghi.micromon.components.forms.valueOption
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.*
import io.kvision.form.FormType
import io.kvision.form.formPanel
import io.kvision.form.select.SelectInput
import io.kvision.form.select.SelectRemoteInput
import io.kvision.form.text.Text
import io.kvision.form.text.TextInput
import io.kvision.html.*
import io.kvision.modal.Alert
import io.kvision.modal.Confirm
import io.kvision.modal.Modal
import io.kvision.navbar.navLink
import io.kvision.toast.Toast
import io.kvision.utils.perc
import kotlinx.serialization.Serializable
import kotlin.js.Date


fun Widget.onGoToDashboard() {
	onGo(DashboardView.path())
}

class DashboardView : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.register("^${path()}$") {
				viewport.setView(DashboardView())
			}
		}

		fun path() = "/dashboard"

		fun go() {
			routing.go(path())
		}
	}

	enum class SharingOption(
		override val id: String,
		val label: String,
		val filter: (User.Session, ProjectData) -> Boolean
	) : Identified {

		Mine("my", "My Projects", { user, project ->
			user.id === project.owner.id
		}),

		Shared("shared", "Shared Projects", { user, project ->
			user.id !== project.owner.id
		}),

		All("all", "All Projects", { _, _ ->
			true
		});


		companion object {

			operator fun get(id: String?): SharingOption? =
				values().find { it.id === id }
		}
	}


	override val elem = Div(classes = setOf("dock-stage", "dashboard"))

	private val selectionElem = Div(classes = setOf("selection"))
	private val selectionButtonsElem = Div(classes = setOf("selection-buttons"))
	private val projectsElem = Div(classes = setOf("projects"))
	private val runningJobsElem = Div(classes = setOf("running-jobs", "dock-window"))
	private val searchResultsElem = Span(classes = setOf("search-results"))
	private val searchBox = TextInput(classes = setOf("search-box"))
	private val sharingSelect = SelectInput(
		SharingOption.values().map { it.id to it.label },
		classes = setOf("sharing")
	).apply {
		value = (Storage.projectSharingFilter ?: SharingOption.All).id
	}

	private val projects = ArrayList<ProjectData>()
	private val projectElems = HashMap<ProjectData,Widget>()

	/** indexed by projectId */
	private val projectSizes = HashMap<String,ProjectSizes?>()

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
			}
		}

		// if we're not logged in, show a friendly message
		if (Session.get() == null) {
			elem.div(classes = setOf("dock-window")) {
				div(classes = setOf("not-logged-in")) {

					AppScope.launch {

						// find out the auth mode of the server
						val info = Viewport.adminInfo.get()
						when (info.authType) {

							AuthType.Login -> {
								p("Please login before using this application.")
								p("If you do not have an account, contact your administrator to get one.")
								button("Login", "fas fa-sign-in-alt")
									.onGoToLogin()
							}

							AuthType.ReverseProxy -> {
								p("You have not been granted access to this application. Contact your administrator to request access.")
							}

							AuthType.None -> {
								p("This should be imposible to see... but you're seeing it anyway, so, uhh... congrats?")
								p("Here's a prize I guess:")
								div {
									style {
										fontSize = 500.perc
										color = Color.hex(0xc5d0db)
									}
									icon("fas fa-award")
									icon("fas fa-trophy")
									icon("fas fa-medal")
								}
							}
						}
					}
				}
			}
			return
		}

		// add a de-select handler
		projectsElem.onClick {
			if (projectElems.values.any { it.hasCssClass("selected") }) {
				selectNothing()
			}
		}

		// layout the page
		elem.div(classes = setOf("dock-window")) {
			add(selectionElem)
			add(selectionButtonsElem)
		}
		elem.add(projectsElem)
		elem.add(runningJobsElem)

		// wire up events
		searchBox.onEvent {
			keyup = {
				updateSearchResults()
			}
		}
		sharingSelect.onEvent {
			change = e@{
				Storage.projectSharingFilter = SharingOption[sharingSelect.value]
				updateSearchResults()
			}
		}

		loadProjects {
			selectNothing()
		}
		loadRunningJobs()
	}

	override fun close() {

		// close any running projects
		runningJobsElem.getChildren()
			.filterIsInstance<RunningProject>()
			.forEach { it.close() }
	}

	private fun selectNothing() {

		// clear selected project elems
		for (elem in projectElems.values) {
			elem.removeCssClass("selected")
		}

		selectionElem.removeAll()
		selectionButtonsElem.removeAll()

		updateSearchResults()

		selectionElem.div {
			div(classes = setOf("search-box-container")) {
				add(searchBox)
				iconStyled("fas fa-search", classes = setOf("search-box-icon"))
			}
			div(classes = setOf("search-second-container")) {
				add(sharingSelect)
				add(searchResultsElem)
			}
		}

		// turn off some stuff for demo mode
		val isDemo = Session.get()?.isdemo ?: true

		// show a button to create a new project
		selectionButtonsElem.button("Create new project", icon = "fas fa-plus-square").enableClickIf(!isDemo) {

			Modal(
				caption = "Create new project",
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup")
			).apply modal@{

				val form = formPanel<CreateProjectArgs>(type = FormType.HORIZONTAL) {
					add(CreateProjectArgs::name, Text(label = "Name"), required = true)
				}
				val errorsElem = div()
				val createButton = button("Create").onClick create@{

					// make sure we have valid input
					if (!form.validate()) {
						return@create
					}
					val args = form.getData()

					disabled = true

					AppScope.launch {

						// create the project on the server
						val project = try {
							Services.projects.create(args.name)
						} catch (t: Throwable) {
							errorsElem.removeAll()
							errorsElem.errorMessage(t)
							return@launch
						}

						this@modal.hide()

						// add the project locally
						projects.add(0, project)

						// update the UI
						searchBox.value = null
						updateSearchResults()
						select(project)
					}
				}

				form.setSubmitButton(createButton)

				show()
			}
		}

		// show a button to go do the sessions view
		selectionButtonsElem.button("Go to Sessions", icon = "fas fa-server")
			.onClick {
				SessionsView.go()
			}
	}

	private fun select(project: ProjectData) {

		// update selected project elems
		for (elem in projectElems.values) {
			elem.removeCssClass("selected")
		}
		projectElems[project]?.addCssClass("selected")

		selectionElem.removeAll()
		selectionButtonsElem.removeAll()

		selectionElem.div(classes = setOf("description")) {

			fun entry(label: String, block: Div.() -> Unit) {
				div(classes = setOf("entry")) {
					div(classes = setOf("label")) {
						content = "$label:"
					}
					div(classes = setOf("content")) {
						block()
					}
				}
			}

			entry("Project") {
				content = project.numberedName
			}

			entry("Created") {
				content = Date(project.created).toLocaleString()
			}

			entry("Size") {
				val loading = loading()
				AppScope.launch {
					val sizes = getProjectSizes(project)
					remove(loading)
					span(sizes.filesystemBytes?.toBytesString() ?: "???")
				}
			}
		}

		selectionButtonsElem.button("Open", icon = "far fa-eye")
			.onGoToProject(project)

		// show the sharing button
		val sharingButton = selectionButtonsElem.button("Sharing", icon = "fas fa-share-alt")
		sharingButton.enableClickIf(project.canWrite()) {
			showSharingPopup(project)
		}

		// show the filesystem location button
		selectionButtonsElem.add(PathPopup.button("Project filesystem location", project.path))

		// show a delete button
		val deleteButton = selectionButtonsElem.button("", icon = "fas fa-trash-alt")
		deleteButton.enableClickIf(project.canWrite()) {
			Confirm.show(
				caption = "Delete project?",
				text = "Are you sure you want to delete the project: ${project.numberedName}"
			) {
				deleteButton.enabled = false
				AppScope.launch {

					try {
						Services.projects.delete(project.owner.id, project.projectId)
					} catch (t: Throwable) {
						Alert.show(
							caption = "Error deleting project",
							text = t.message ?: "Unknown error"
						)
						deleteButton.enabled = true
						return@launch
					}

					// update the view
					projects.remove(project)
					updateSearchResults()
					selectNothing()
				}
			}
		}
	}

	private fun loadProjects(block: () -> Unit) {

		projectsElem.removeAll()

		val session = Session.get() ?: return

		AppScope.launch {

			// load the projects
			// but show at least a little delay, so the user sees we're doing work
			val loadingElem = projectsElem.loading("Fetching projects ...")
			val projects = try {
				delayAtLeast(200) { Services.projects.list(session.id) }
			} catch (t: Throwable) {
				projectsElem.errorMessage(t)
				return@launch
			} finally {
				projectsElem.remove(loadingElem)
			}

			// update the view
			this@DashboardView.projects.apply {
				clear()
				addAll(projects)
			}
			updateSearchResults()

			block()
		}
	}

	private fun showProjects(projects: List<ProjectData>) {

		projectsElem.removeAll()
		projectElems.clear()

		if (projects.isEmpty()) {

			projectsElem.div(
				if (searchBox.value != null) {
					"No projects here. Try a different search query."
				} else {
					"No projects here. Try creating a new one."
				},
				classes = setOf("empty")
			)

		} else {

			for (project in projects) {
				projectElems[project] = projectsElem.div(classes = setOf("project", "dock-window")) {

					div(classes = setOf("project-title")) {

						div(project.numberedName, classes = setOf("project-name"))

						// if it's not our project, show the owner
						if (!project.isOwner()) {
							chip(classes = setOf("project-owner")) {
								iconStyled("fas fa-user", classes = setOf("icon"))
								span(project.owner.name)
							}
						}
					}

					// show representative images
					div(classes = setOf("representative-images")) {
						val imageSize = ImageSize.Small
						if (project.images.isNotEmpty()) {
							for (url in project.images) {
								div(classes = setOf("representative-image-container")) {
									image(url)
										.ifNonExistentUsePlaceholder(imageSize)
								}
							}
						} else {
							image("/img/placeholder/${imageSize.id}")
						}
					}

				}.apply {
					onClick { event ->

						select(project)

						// don't let the de-select handler run
						event.stopPropagation()
					}
				}
			}
		}
	}

	private fun updateSearchResults() {

		var projects: List<ProjectData> = projects

		// apply the sharing filter
		val sharingOption = SharingOption[sharingSelect.value]
			?: SharingOption.All
		Session.get()?.let { session ->
			projects = projects.filter { project ->
				sharingOption.filter(session, project)
			}
		}

		// apply the search query, if any
		val query = searchBox.value
			?.lowercase()
		if (query != null) {
			projects = projects.filter { query in it.numberedName.lowercase() }
		}

		// update the UI with the search results
		val noun = if (this.projects.size == 1) {
				"project"
			} else {
				"projects"
			}
		searchResultsElem.content = "Showing ${projects.size} of ${this.projects.size} $noun"
		showProjects(projects)
	}

	private fun loadRunningJobs() {

		runningJobsElem.apply {

			// hide the running jobs UI by default
			visible = false

			h1("Currently Running Jobs")
		}

		val session = Session.get() ?: return

		AppScope.launch {

			// find out which projects, if any, have running jobs
			val runningProjects = Services.projects.listRunning(session.id)
			for (project in runningProjects) {
				runningJobsElem.add(RunningProject(project))
			}

			// if we got any running projects, show the UI
			if (runningProjects.isNotEmpty()) {
				runningJobsElem.visible = true
			}
		}
	}

	private suspend fun getProjectSizes(project: ProjectData): ProjectSizes {

		// check the cache first
		projectSizes[project.projectId]
			?.let { return it }

		// cache miss, ask the server
		val sizes = try {
			Services.projects.countSizes(project.owner.id, project.projectId)
		} catch (t: Throwable) {
			t.reportError()
			ProjectSizes.empty()
		}

		projectSizes[project.projectId] = sizes

		return sizes
	}

	private fun showSharingPopup(project: ProjectData) {
		Modal(
			caption = "Project sharing",
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup", "project-sharing-popup")
		).apply modal@{

			h1("Project:")
			p(project.numberedName)

			h1("Shared With:")
			val sharedUsersElem = P(classes = setOf("shared-users"))
			add(sharedUsersElem)
			val notSharedElem = Span("This project is not shared with anyone", classes = setOf("empty"))

			val userSelect = SelectRemoteInput(
				FormServiceManager,
				IFormService::users
			)
			val addUserButton = Button("Add", icon = "fas fa-user-plus", classes = setOf("add-user-button")).apply {
				// wait until later before enabling this button
				enabled = false
			}
			div(classes = setOf("add-user")) {
				add(userSelect)
				add(addUserButton)
			}

			AppScope.launch load@{

				// load the current sharing settings
				val loading = sharedUsersElem.loading("loading ...")
				val sharedUsers = try {
					Services.projects.getSharedUsers(project.owner.id, project.projectId)
						.toMutableList()
				} catch (t: Throwable) {
					sharedUsersElem.errorMessage(t)
					return@load
				} finally {
					sharedUsersElem.remove(loading)
				}

				fun updateEmpty() {
					val showingEmpty = sharedUsersElem.getChildren().any { it === notSharedElem }
					val showingOthers = sharedUsersElem.getChildren().any { it !== notSharedElem }
					if (!showingOthers && !showingEmpty) {
						sharedUsersElem.add(notSharedElem)
					} else if (showingOthers && showingEmpty) {
						sharedUsersElem.remove(notSharedElem)
					}
				}

				fun showUserChip(user: UserData): ButtonChip {

					// create and add the chip
					val chip = sharedUsersElem.buttonChip("fas fa-times") {
						content = user.name ?: "(unknown user with id = ${user.id})"
					}
					updateEmpty()

					chip.button.onClick {

						// disable the button until we're done
						chip.waiting = true

						AppScope.launch {

							// update the server
							try {
								Services.projects.unsetSharedUser(project.owner.id, project.projectId, user.id)
							} catch (t: Throwable) {
								t.reportError()
								Toast.error("Failed to unshare with user:", t.message ?: "(unknown reason)")
								return@launch
							}

							// it worked!
							sharedUsers.remove(user)
							sharedUsersElem.remove(chip)
							updateEmpty()
						}
					}

					return chip
				}

				// show the shared users, if any
				for (user in sharedUsers) {
					showUserChip(user)
				}

				// wire up events
				userSelect.onEvent {
					change = {

						// allow adding if we have selected a user
						addUserButton.enabled = userSelect.value != null
					}
				}
				addUserButton.onClick {

					// get the selected user, if any
					val userId = userSelect.value
						?: return@onClick
					val userName = userSelect.valueOption
						?.textContent
						?: return@onClick
					val user = UserData(userId, userName)

					// ignore duplicates
					if (user in sharedUsers) {
						return@onClick
					}

					// remove the selection
					userSelect.value = null
					addUserButton.enabled = false

					// make the chip, but disable the button until we hear back from the server
					val chip = showUserChip(user)
					chip.waiting = true

					AppScope.launch set@{

						// update the server
						try {
							Services.projects.setSharedUser(project.owner.id, project.projectId, user.id)
						} catch (t: Throwable) {
							t.reportError()
							Toast.error("Failed to share with user:", t.message ?: "(unknown reason)")
							sharedUsersElem.remove(chip)
							updateEmpty()
							return@set
						}

						// it worked! enable the chip
						sharedUsers.add(user)
						chip.waiting = false
					}
				}
			}

			// add a button to close the popup
			val okButton = Button("Ok")
			okButton.onClick {
				this@modal.hide()
			}
			addButton(okButton)

			show()
		}
	}
}

@Serializable
data class CreateProjectArgs(
	val name: String
)
