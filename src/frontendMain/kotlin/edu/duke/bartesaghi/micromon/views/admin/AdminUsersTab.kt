package edu.duke.bartesaghi.micromon.views.admin

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.TabulatorProxy
import edu.duke.bartesaghi.micromon.components.forms.copyToClipboard
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.services.AdminInfo
import edu.duke.bartesaghi.micromon.services.Services
import edu.duke.bartesaghi.micromon.services.UserProcessorCheck
import edu.duke.bartesaghi.micromon.services.UserProperties
import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.form.text.TextInput
import io.kvision.form.text.text
import io.kvision.form.text.textInput
import io.kvision.html.*
import io.kvision.modal.Alert
import io.kvision.modal.Confirm
import io.kvision.modal.Modal
import io.kvision.remote.ServiceException
import io.kvision.tabulator.*
import kotlinx.browser.window


class AdminUsersTab(val elem: Container, val info: AdminInfo) {

	private val myUserId = Session.get()?.id

	private val proxy = TabulatorProxy<User>()

	private fun showUserForm(user: User?, onSave: (User, UserProperties) -> Unit) {
		Modal(
			caption = if (user == null) {
				"Add User"
			} else {
				"Edit User"
			},
			escape = true,
			closeButton = true,
			classes = setOf("admin-popup", "admin-user-popup", "max-height-dialog")
		).apply modal@{

			// show a box to type the id, if needed
			val useridText = text {
				label = "User ID"
				value = user?.id ?: ""
				disabled = user != null
			}

			val nameText = text {
				label = "Display Name"
				value = user?.name ?: ""
			}

			val osUsernameText = text {
				label = "OS Username"
				value = user?.osUsername
			}

			val runasInfo = RunasInfo()
			runasInfo.username = user?.osUsername
			add(runasInfo)

			osUsernameText.onEvent {
				input = {
					runasInfo.username = osUsernameText.value
				}
			}

			// show the permissions
			val permissionChecks = div(classes = setOf("block")).run {
				h1("Permissions")
				User.Permission.values()
					.toList()
					.associateWith { perm ->
						checkBox(
							value = user != null && perm in user.permissions,
							label = perm.name
						) {

							// HACKHACK: don't let administrators remove their own administrative permission
							// this could lead to an unrecoverable state where no one is an administrator anywhere!
							if (perm == User.Permission.Admin) {
								if (myUserId == user?.id) {
									disabled = true
									title = "Can't remove own administrative permissions"
								} else {
									title = perm.description
								}
							} else {
								if (user?.isAdmin == true) {
									disabled = true
									title = "Administrators have all permissions"
								} else {
									title = perm.description
								}
							}
						}
					}
			}

			var groupChecks: Map<Group,CheckBox>? = null
			var properties: List<UserPropertyControls>? = null

			val saveButton = Button("Save")
			saveButton.onClick {

				@Suppress("NAME_SHADOWING")
				val groupChecks = groupChecks ?: return@onClick
				@Suppress("NAME_SHADOWING")
				val properties = properties ?: return@onClick

				// save user from form data
				onSave(
					User(
						id = user?.id
							?: useridText.value
							?: return@onClick,
						name = nameText.value
							?: return@onClick,
						permissions = permissionChecks
							.filter { (_, check) -> check.value }
							.map { (perm, _) -> perm }
							.toSet(),
						groups = groupChecks
							.filter { (_, check) -> check.value }
							.map { (groupId, _) -> groupId }
							.toSet(),
						osUsername = osUsernameText.value
					),
					UserProperties(
						props = properties
							.mapNotNull {
								val key = it.keyText.value
									?.takeIf { it.isNotBlank() }
									?: return@mapNotNull null
								val value = it.valueText.value
									?: ""
								key to value
							}
							.associate { (k, v) -> k to v }
					)
				)

				// save properties too

				this@modal.hide()
			}
			addButton(saveButton)

			// start with the save button disabled
			saveButton.enabled = false

			// but enable it when all the parts are ready
			fun updateSaveButton() {
				groupChecks ?: return
				properties ?: return
				saveButton.enabled = true
			}

			// show the groups
			div(classes = setOf("block")) {
				h1("Groups")

				val loading = loading("Loading ...")
				AppScope.launch {

					val groups = try {
						Services.admin.getGroups()
					} catch (t: Throwable) {
						errorMessage("Error loading groups:")
						errorMessage(t)
						return@launch
					} finally {
						remove(loading)
					}

					groupChecks = groups.associateWith { group ->
						checkBox(
							value = user?.hasGroup(group) ?: false,
							label = group.name
						)
					}

					updateSaveButton()
				}
			}

			// show the properties
			div(classes = setOf("block")) {
				h1("Custom Properties")
				AppScope.launch {

					val p = if (user != null) {
						val loading = loading("Loading ...")
						try {
							Services.admin.getUserProperties(user.id)
						} catch (t: Throwable) {
							errorMessage("Error loading custom properties:")
							errorMessage(t)
							return@launch
						} finally {
							remove(loading)
						}
					} else {
						UserProperties()
					}

					properties = showProperties(p)
					updateSaveButton()
				}
			}

			show()
		}
	}

	fun add(user: User, properties: UserProperties) {
		AppScope.launch addLaunch@{

			// tell the server
			try {
				Services.admin.createUser(user)
				Services.admin.setUserProperties(user.id, properties)
			} catch (t: Throwable) {
				t.alert()
				return@addLaunch
			}

			// update the table
			proxy.items = (proxy.items + user)
				.sortedBy { it.id }
		}
	}

	fun edit(user: User, properties: UserProperties) {
		AppScope.launch editLaunch@{

			// tell the server
			try {
				Services.admin.editUser(user)
				Services.admin.setUserProperties(user.id, properties)
			} catch (t: Throwable) {
				t.alert()
				return@editLaunch
			}

			// update the table
			proxy.items = proxy.items
				.filter { it.id != user.id }
				.toMutableList()
				.also { it.add(user) }
				.sortedBy { it.id }
		}
	}

	private fun delete(user: User) {
		AppScope.launch deleteLaunch@{

			// tell the server
			try {
				Services.admin.deleteUser(user.id)
			} catch (t: Throwable) {
				t.alert()
				return@deleteLaunch
			}

			// update the table
			proxy.items = proxy.items
				.filter { it.id != user.id }
		}
	}

	private fun showDeleteForm(user: User) {
		Confirm.show(
			"Confirm Deletion",
			"Delete user ${user.id}?"
		) {
			delete(user)
		}
	}

	private fun deletePassword(user: User) {
		AppScope.launch delpwLaunch@{

			// tell the server
			try {
				Services.admin.deleteUserPassword(user.id)
			} catch (t: Throwable) {
				t.alert()
				return@delpwLaunch
			}

			// update the table
			proxy.items = proxy.items
				.filter { it.id != user.id }
				.toMutableList()
				.also { it.add(user.copy(haspw = false)) }
				.sortedBy { it.id }

			// success!
			Alert.show(
				caption = "Success",
				text = "The password for user ${user.id} has been deleted"
			)
		}
	}

	private fun showDeletePasswordForm(user: User) {
		Confirm.show(
			"Delete password for user ${user.id}?",
			"This user will no longer be able to login using their password."
				+ " An administrator must create a one-time login link for anyone to access this account."
		) {
			deletePassword(user)
		}
	}

	private fun showLoginLinkForm(user: User) {
		Modal(
			caption = "One-time login link",
			escape = true,
			closeButton = true,
			classes = setOf("admin-popup")
		).apply {

			p("Create a one-time link anyone can use to log into this user's account.")
			p("Make sure to only share this link with the intended recipient.")
			p("After the link is used once, it can't be used again.")
			p("Generating a new link will replace any old link.")

			val generate = button("Generate")
			val linkText = textInput {
				value = ""
			}
			button("", icon = "fas fa-copy").apply {
				title = "Copy link to clipboard"
			}.onClick {
				linkText.copyToClipboard()
			}
			show()

			generate.onClick {
				AppScope.launch {
					try {

						// get the secret code from the backend
						val link = Services.admin.generateLoginLink(user.id)

						// add the link prefix for this HTTP server
						linkText.value = "${window.location.origin}$link"

					} catch (ex: ServiceException) {
						Alert.show(
							caption = "Error",
							text = ex.message ?: "Unknown error"
						)
					}
				}
			}
		}
	}

	init {
		// make a fancy table to show all the users
		proxy.tabulator = Tabulator.create(
			classes = setOf("table"),
			options = TabulatorOptions(
				layout = Layout.FITDATA,
				columns = listOf(
					ColumnDefinition(
						"",
						cssClass = "actionsColumn",
						formatterComponentFunction = { _, _, key ->
							Div().apply {

								val user = proxy.resolve(key)

								button("", icon = "far fa-edit").apply {
									title = "Edit this user"
								}.onClick {
									showUserForm(user) { user, properties ->
										edit(user, properties)
									}
								}

								button("", icon = "fas fa-trash").apply {
									title = "Delete this user"
									if (user.id == myUserId) {
										disabled = true
										title = "Can't delete yourself"
									}
								}.onClick {
									showDeleteForm(user)
								}

								button("", icon = "fas fa-lock").apply {
									title = "Delete this user's password."
									if (!user.haspw) {
										disabled = true
										title = "User has no password"
									} else if (user.id == myUserId) {
										disabled = true
										title = "Can't delete your own password"
									}
								}.onClick {
									showDeletePasswordForm(user)
								}

								button("", icon = "fas fa-sign-in-alt").apply {
									title = "Create a one-time login link for this user"
									enabled = info.authType.hasUsers
								}.onClick {
									showLoginLinkForm(user)
								}
							}
						},
						headerSort = false,
						minWidth = 100
					),
					ColumnDefinition(
						"User ID",
						formatterComponentFunction = { _, _, key ->
							val user = proxy.resolve(key)
							Span(user.id)
						},
						headerSort = false
					),
					ColumnDefinition(
						"Permissions",
						formatterComponentFunction = { _, _, key ->
							val user = proxy.resolve(key)
							Span(user.permissions.joinToString(", ") { it.name })
						},
						headerSort = false
					),
					ColumnDefinition(
						"Groups",
						formatterComponentFunction = { _, _, key ->
							val user = proxy.resolve(key)
							Span(user.groups.joinToString(", ") { it.name })
						},
						headerSort = false
					)
				),
				pagination = PaginationMode.LOCAL,
				paginationSize = 10
			),
		)

		// make a text box to search for users
		val textbox = TextInput(classes = setOf("useridSearch")).apply {
			placeholder = "Search by user ID"
			onEvent {
				input = {

					val query = value
					if (query != null) {

						// set a filter so the userid starts with the search query
						proxy.filter = { user ->
							user.id.startsWith(query)
						}

					} else {

						// remove the filter
						proxy.filter = { true }
					}
				}
			}
		}

		// make a button to add a new user
		val showAddUserButton = Button("Add User", icon = "fas fa-plus").onClick {
			showUserForm(null) { user, properties ->
				add(user, properties)
			}
		}

		// show disclaimer in Auth=None + dev mode
		if (!info.authType.hasUsers && info.debug) {
			elem.div(classes = setOf("empty")) {
				p("""
						|Authentication is currently disabled, but development mode is on.
						|You will be able to create and edit users for testing,
						|but you can't use any of the users to log in,
						|and you will always be the Aministrator.
					""".trimMargin())
			}
		}

		// layout elements
		elem.addCssClass("tab")
		elem.div {
			add(textbox)
			add(proxy.tabulatorOrThrow)
			add(showAddUserButton)
		}

		AppScope.launch {

			// finally, get the users
			val loadingElem = elem.loading("Fetching users ...")
			try {
				delayAtLeast(200) {
					proxy.items = Services.admin.getUsers()
						.sortedBy { it.id }
				}
			} catch (t: Throwable) {
				elem.errorMessage(t)
			} finally {
				elem.remove(loadingElem)
			}
		}
	}
}


class RunasInfo : Div(classes = setOf("runas-info")) {

	var username: String? = null
		set(value) {
			field = value
			update()
		}

	private var check: UserProcessorCheck? = null
	private var checking: String? = null
	private var checkError: Throwable? = null

	private val elem = Div(classes = setOf("main"))

	private val checkButton = Button("Check", icon = "fas fa-user-cog")

	init {

		// layout the UI
		add(elem)
		add(checkButton)

		// wire up events
		checkButton.onClick e@{
			val username = username
				?: return@e
			AppScope.launch {
				check(username)
			}
		}

		update()
	}

	private fun update() {

		elem.removeAll()

		// copy state to local vars, because something something threads
		val checking = checking
		val checkError = checkError
		val username = username
		val check = check

		checkButton.enabled = username != null

		if (checking != null) {

			elem.loading("Checking runas executable for username $checking ...")
			checkButton.enabled = false

		} else if (checkError != null) {

			elem.errorMessage(checkError)

		} else if (username != null) {

			if (check != null) {

				elem.div {
					iconStyled("far fa-file", classes = setOf("icon"))
					span(check.path)
				}

				if (check.username != null) {

					elem.div(classes = setOf("success-message")) {
						iconStyled("fas fa-check", classes = setOf("icon"))
						span("user processor executable available")
					}

					if (check.username == username) {
						elem.div(classes = setOf("success-message")) {
							iconStyled("fas fa-check", classes = setOf("icon"))
							span("Success: whoami=\"${check.username}\"")
						}
					} else {
						elem.div(classes = setOf("error-message")) {
							iconStyled("fas fa-exclamation-triangle", classes = setOf("icon"))
							span("Failure: whoami=\"${check.username}\", expected=\"$username\"")
						}
					}

				} else if (check.problems != null) {

					elem.div(classes = setOf("error-message")) {
						div {
							iconStyled("fas fa-exclamation-triangle", classes = setOf("icon"))
							span("host processor executable not available:")
						}
						ul {
							for (problem in check.problems) {
								li {
									span(problem)
								}
							}
						}
					}
				}

			} else {
				elem.span("Ready to check user processor executable", classes = setOf("empty"))
			}

		} else {
			elem.span("No OS username set", classes = setOf("empty"))
		}
	}

	suspend fun check(username: String) {

		checking = username
		checkError = null
		check = null
		update()

		try {
			check = Services.admin.checkUserProcessor(username)
		} catch (t: Throwable) {
			checkError = t
		} finally {
			checking = null
		}

		update()
	}
}


class UserPropertyControls() : Tr() {

	val keyText = TextInput()
	val valueText = TextInput()
	val deleteButton = Button("", icon = "fas fa-trash")

	init {
		// once again, Kotlin DSLs are dumb ... =(
		val self = this
		td {
			add(self.keyText)
		}
		td {
			add(self.valueText)
		}
		td {
			add(self.deleteButton)
		}
	}

	constructor(key: String, value: String) : this() {
		keyText.value = key
		valueText.value = value
	}
}


private fun Container.showProperties(properties: UserProperties): List<UserPropertyControls> {

	val controls = ArrayList<UserPropertyControls>()

	div(classes = setOf("user-property-builder")) {

		div(classes = setOf("help")) {
			content = "These property values are available in cluster job templates using the syntax: user.properties.<key>"
		}

		val propsElem = table(classes = setOf("props"))

		// show a header
		propsElem.thead {
			tr {
				td("Key")
				td("Value")
			}
		}

		val propsBody = propsElem.tbody()

		fun UserPropertyControls.attach() {
			val propControls = this
			controls.add(propControls)
			propsBody.add(propControls)
			deleteButton.onClick {

				controls.removeAll { it === propControls }
				propsBody.remove(propControls)

				// always make sure at least one property controls is showing, even if its just empty
				if (controls.isEmpty()) {
					UserPropertyControls().attach()
				}
			}
		}

		// show controls for existing properties
		for ((key, value) in properties.props) {
			UserPropertyControls(key, value).attach()
		}

		// if there are no current properties, make space for a new one
		if (controls.isEmpty()) {
			UserPropertyControls().attach()
		}

		// add a button to add new props
		val newButton = button("", icon = "fas fa-plus")
		newButton.onClick {
			UserPropertyControls().attach()
		}
	}

	return controls
}
