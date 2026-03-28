package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.CopyText
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink
import io.kvision.toast.Toast


fun Widget.onGoToApps() {
	onGo(AppsView.path())
}

class AppsView : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.register("^${path()}$") {
				viewport.setView(AppsView())
			}
		}

		fun path() = "/apps"
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "apps-view"))

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Your Account", icon = "fas fa-user")
					.onGoToYourAccount()
				navLink("Apps", icon = "fas fa-puzzle-piece")
					.onGoToApps()
			}
		}

		val bar = elem.div(classes = setOf("block-view-bar"))
		bar.h1("Apps")
		val content = elem.div(classes = setOf("block-view-content"))

		val session = Session.get()
		if (session != null) {
			content.add(AppsPanel(session))
		} else {
			content.h1("Not logged in")
		}
	}
}


/**
 * A self-contained panel for managing app tokens (requests and granted apps).
 * Used both by AppsView (standalone page) and YourAccountView (embedded in a tab).
 */
class AppsPanel(session: User.Session) : Div(classes = setOf("tab")) {

	private val requests = ArrayList<AppTokenRequestData>()
	private val apps = ArrayList<AppTokenData>()
	private val requestsElem = Div()
	private val appsElem = Div()

	init {
		AppScope.launch {

			val loadingElem = loading("Loading apps...")
			try {
				requests.setAll(Services.apps.tokenRequests(session.id))
				apps.setAll(Services.apps.appTokens(session.id))
			} catch (t: Throwable) {
				errorMessage(t)
				return@launch
			} finally {
				remove(loadingElem)
			}

			batch {
				h1("App Requests")
				add(requestsElem)
				updateRequests()
				h1("Apps")
				add(appsElem)
				updateApps()
			}
		}
	}

	private fun updateRequests() {

		requestsElem.removeAll()

		if (requests.isEmpty()) {
			requestsElem.div("No app requests yet.", classes = setOf("empty", "spaced"))
			return
		}

		val table = Table(classes = setOf("requests"))
		requestsElem.add(table)
		for (request in requests) {
			addRequestRow(table, request)
		}
	}

	private fun addRequestRow(table: Table, request: AppTokenRequestData) {

		val row = Tr(classes = setOf("request"))
		val allowButton = Button("Allow", icon = "fas fa-thumbs-up")
			.apply {
				title = "Grant this app access to your account."
			}
		val denyButton = Button("Deny", icon = "fas fa-thumbs-down")
			.apply {
				title = "Don't grant this app to access your account and delete the request."
			}

		fun useButtons(block: suspend () -> Unit) {
			allowButton.enabled = false
			denyButton.enabled = false
			AppScope.launch {
				try {
					block()
				} catch (t: Throwable) {
					Toast.error(t.message ?: "(unknown error)")
				} catch (err: dynamic) {
					Toast.error("$err")
				} finally {
					allowButton.enabled = true
					denyButton.enabled = true
				}
			}
		}

		// wire up events
		allowButton.onClick {
			useButtons {
				val acceptance = Services.apps.acceptTokenRequest(request.requestId)
				requests.remove(request)
				apps.add(acceptance.tokenInfo)
				showToken(row, request, acceptance.token)
				updateApps()
			}
		}
		denyButton.onClick {
			useButtons {
				Services.apps.rejectTokenRequest(request.requestId)
				requests.remove(request)
				row.parent?.remove(row)
				Toast.success("Deined request from ${request.appName}")
			}
		}

		// layout the UI
		table.add(row)
		row.td(request.appName, classes = setOf("name"))
		val permsTd = row.td()
		permsTd.div("Requested permissions:")
		addPermissions(permsTd, request.permissions)
		row.td(classes = setOf("buttons")) {
			add(allowButton)
			add(denyButton)
		}
	}

	private fun updateApps() {

		appsElem.removeAll()

		if (apps.isEmpty()) {
			appsElem.div("No apps yet.", classes = setOf("empty", "spaced"))
			return
		}

		val table = Table(classes = setOf("apps"))
		appsElem.add(table)
		for (app in apps) {
			addAppRow(table, app)
		}
	}

	private fun addAppRow(table: Table, app: AppTokenData) {

		val row = Tr(classes = setOf("app"))
		val revokeButton = Button("Revoke", icon = "fas fa-trash")
			.apply {
				title = "No longer allow this app to access your account."
			}

		fun useButtons(block: suspend () -> Unit) {
			revokeButton.enabled = false
			AppScope.launch {
				try {
					block()
				} catch (t: Throwable) {
					Toast.error(t.message ?: "(unknown error)")
				} catch (err: dynamic) {
					Toast.error("$err")
				} finally {
					revokeButton.enabled = true
				}
			}
		}

		// wire up events
		revokeButton.onClick {
			useButtons {
				Services.apps.revokeToken(app.tokenId)
				apps.remove(app)
				row.parent?.remove(row)
				Toast.success("Revoked access for ${app.appName}")
			}
		}

		// layout the UI
		table.add(row)
		row.td(app.appName, classes = setOf("name"))
		val permsTd = row.td()
		permsTd.div("Granted permissions:")
		addPermissions(permsTd, app.permissions)
		row.td(classes = setOf("buttons")) {
			add(revokeButton)
		}
	}

	private fun addPermissions(container: Container, permissions: List<AppPermissionData>) {
		container.table(classes = setOf("permissions")) {
			for (permission in permissions) {
				tr(classes = setOf("permission")) {
					td(permission.appPermissionId, classes = setOf("title"))
					td(permission.description, classes = setOf("description"))
				}
			}
		}
	}

	private fun showToken(row: Tr, request: AppTokenRequestData, token: String) {

		row.removeAll()

		row.td {
			div(request.appName, classes = setOf("name"))
			div("Access approved!")
		}
		row.td {
			colspan = 2
			div(classes = setOf("token-instructions")) {
				div("Copy this token into the app to complete the connection:")
				add(CopyText(token, "Copied app token to the clipboard"))
				div("""
					|This token will be shown only once right here.
					|Once you leave this page, the token will no longer be available.
					|If you lose your token and still need to connect your app,
					|you will need to revoke the app (down below) and then have the app request a new token.
				""".trimMargin())
			}
		}
	}
}
