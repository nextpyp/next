package edu.duke.bartesaghi.micromon.views.admin

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.TabulatorProxy
import edu.duke.bartesaghi.micromon.services.Services
import io.kvision.core.Container
import io.kvision.form.text.textInput
import io.kvision.html.*
import io.kvision.modal.Confirm
import io.kvision.modal.Modal
import io.kvision.tabulator.*


class AdminGroupsTab(val elem: Container) {

	private val proxy = TabulatorProxy<Group>()

	private fun showGroupForm(group: Group?, onSave: (Group) -> Unit) {
		Modal(
			caption = if (group == null) {
				"Add Group"
			} else {
				"Edit Group"
			},
			escape = true,
			closeButton = true,
			classes = setOf("admin-popup")
		).apply modal@{
			AppScope.launch {

				val nameText = textInput {
					placeholder = "Name"
					value = group?.name ?: ""
				}

				button("Save").onClick addGroup@{

					// get form data
					val name = nameText.value
						?: return@addGroup

					onSave(Group(name, group?.id))
					this@modal.hide()
				}

				show()
			}
		}
	}

	private fun add(group: Group) {
		AppScope.launch addLaunch@{

			// tell the server
			val newGroup = try {
				Services.admin.createGroup(group)
			} catch (t: Throwable) {
				t.alert()
				return@addLaunch
			}

			// update the table
			proxy.items = (proxy.items + newGroup)
				.sortedBy { it.name }
		}
	}

	private fun edit(group: Group) {
		AppScope.launch editLaunch@{

			// tell the server
			try {
				Services.admin.editGroup(group)
			} catch (t: Throwable) {
				t.alert()
				return@editLaunch
			}

			// update the table
			proxy.items = proxy.items
				.filter { it.id != group.id }
				.toMutableList()
				.also { it.add(group) }
				.sortedBy { it.name }
		}
	}

	private fun delete(group: Group) {

		val groupId = group.id ?: return

		AppScope.launch deleteLaunch@{

			// tell the server
			try {
				Services.admin.deleteGroup(groupId)
			} catch (t: Throwable) {
				t.alert()
				return@deleteLaunch
			}

			// update the table
			proxy.items = proxy.items
				.filter { it.id != groupId }
		}
	}

	private fun showDeleteForm(group: Group) {
		Confirm.show(
			"Confirm Deletion",
			"Delete group ${group.name}?"
		) {
			delete(group)
		}
	}

	init {

		// make a fancy table to show all the groups
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

								val group = proxy.resolve(key)

								button("", icon = "far fa-edit").apply {
									title = "Edit this group"
								}.onClick {
									showGroupForm(group) { edit(it) }
								}

								button("", icon = "fas fa-trash").apply {
									title = "Delete this group"
								}.onClick {
									showDeleteForm(group)
								}
							}
						},
						headerSort = false,
						minWidth = 100
					),
					ColumnDefinition(
						"Name",
						formatterComponentFunction = { _, _, key ->
							val group = proxy.resolve(key)
							Span(group.name)
						}
					)
				),
				pagination = PaginationMode.LOCAL,
				paginationSize = 10
			),
		)

		// make a button to add a new group
		val showAddButton = Button("Add Group", icon = "fas fa-plus").onClick {
			showGroupForm(null) { add(it) }
		}

		// layout elements
		elem.addCssClass("tab")
		elem.div {
			add(proxy.tabulatorOrThrow)
			add(showAddButton)
		}

		AppScope.launch {

			// finally, get the groups
			val loadingElem = elem.loading("Fetching groups ...")
			try {
				delayAtLeast(200) {
					proxy.items = Services.admin.getGroups()
						.sortedBy { it.name }
				}
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loadingElem)
			}
		}
	}
}