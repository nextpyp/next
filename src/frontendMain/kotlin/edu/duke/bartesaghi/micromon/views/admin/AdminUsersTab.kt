package edu.duke.bartesaghi.micromon.views.admin

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.TabulatorProxy
import edu.duke.bartesaghi.micromon.components.forms.copyToClipboard
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.services.AdminInfo
import edu.duke.bartesaghi.micromon.services.RunasData
import edu.duke.bartesaghi.micromon.services.Services
import io.kvision.core.Container
import io.kvision.core.onEvent
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

	private fun showUserForm(user: User?, onSave: (User) -> Unit) {
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
			AppScope.launch l@{

				// load the groups
				val loading = loading("Loading ...")
				val groups = try {
					Services.admin.getGroups()
				} catch (t: Throwable) {
					errorMessage("Error loading groups:")
					errorMessage(t)
					return@l
				} finally {
					remove(loading)
				}

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

				// show the groups
				val groupChecks = div(classes = setOf("block")).run {
					h1("Groups")
					groups.associateWith { group ->
						checkBox(
							value = user?.hasGroup(group) ?: false,
							label = group.name
						)
					}
				}

				button("Save").onClick addUser@{

					// get form data
					val userid = user?.id
						?: useridText.value
						?: return@addUser
					val name = nameText.value
						?: return@addUser
					val permissions = permissionChecks
						.filter { (_, check) -> check.value }
						.map { (perm, _) -> perm }
					val groupIds = groupChecks
						.filter { (_, check) -> check.value }
						.map { (groupId, _) -> groupId }

					onSave(User(userid, name, permissions.toSet(), groupIds.toSet()))
					this@modal.hide()
				}

				show()
			}
		}
	}

	fun add(user: User) {
		AppScope.launch addLaunch@{

			// tell the server
			try {
				Services.admin.createUser(user)
			} catch (t: Throwable) {
				t.alert()
				return@addLaunch
			}

			// update the table
			proxy.items = (proxy.items + user)
				.sortedBy { it.id }
		}
	}

	fun edit(user: User) {
		AppScope.launch editLaunch@{

			// tell the server
			try {
				Services.admin.editUser(user)
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
									showUserForm(user) { edit(it) }
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
			showUserForm(null) { add(it) }
		}

		// show disclaimer in Auth=None + dev mode
		if (!info.authType.hasUsers && info.dev) {
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

	private var runas: RunasData? = null
	private var checking: String? = null
	private var checkError: Throwable? = null

	private val emptyMessage = Span("No OS username set", classes = setOf("empty", "spaced"))
	private val readyMessage = Span("Ready to check runas executable", classes = setOf("empty", "spaced"))

	private val elem = Div(classes = setOf("main"))

	private val checkButton = Button("Check", icon = "fas fa-user-cog")

	init {

		// layout the UI
		add(elem)
		add(checkButton)

		// wite up events
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
		val runas = runas

		checkButton.enabled = username != null

		if (checking != null) {

			elem.loading("Checking runas executable for username $checking ...")
			checkButton.enabled = false

		} else if (checkError != null) {

			elem.errorMessage(checkError)

		} else if (username != null) {

			if (runas != null) {

				elem.div {
					iconStyled("far fa-file", classes = setOf("icon"))
					span(runas.path)
				}

				if (runas.ok) {
					elem.div(classes = setOf("success-message")) {
						iconStyled("fas fa-check", classes = setOf("icon"))
						span("runas executable available")
					}
				} else {
					elem.div(classes = setOf("error-message")) {
						div {
							iconStyled("fas fa-exclamation-triangle", classes = setOf("icon"))
							span("runas executable not available:")
						}
						ul {
							for (problem in runas.problems) {
								li {
									span(problem)
								}
							}
						}
					}
				}

			} else {
				elem.add(readyMessage)
			}

		} else {
			elem.add(emptyMessage)
		}
	}

	suspend fun check(username: String) {

		checking = username
		checkError = null
		runas = null
		update()

		try {
			runas = Services.admin.checkRunas(username)
		} catch (t: Throwable) {
			checkError = t
		} finally {
			checking = null
		}

		update()
	}
}
