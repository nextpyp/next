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

	private val requests = ArrayList<AppTokenRequestData>()
	private val apps = ArrayList<AppTokenData>()

	private val requestsElem = Div()
	private val appsElem = Div()

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

		// what's our login status?
		val session = Session.get()

		// show the appropriate form
		if (session != null) {
			elem.show(session)
		} else {
			elem.h1("Not logged in")
		}
	}

	private fun Container.show(session: User.Session) {

		AppScope.launch {

			// check with the backend to see what kind of login system we're using
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

		requestsElem.table(classes = setOf("requests")) {
			for (request in requests) {
				showRequest(request)
			}
		}
	}

	private fun Table.showRequest(request: AppTokenRequestData) {

		// NOTE: the receiver container is a <table>

		val elem = Tr(classes = setOf("request"))
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
				elem.showToken(request, acceptance.token)
				updateApps()
			}
		}
		denyButton.onClick {
			useButtons {
				Services.apps.rejectTokenRequest(request.requestId)
				requests.remove(request)
				elem.parent?.remove(elem)
				Toast.success("Deined request from ${request.appName}")
			}
		}

		// layout the UI
		add(elem)
		elem.td(request.appName, classes = setOf("name"))
		elem.td {
			div("Requested permissions:")
			showPermissions(request.permissions)
		}
		elem.td(classes = setOf("buttons")) {
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

		appsElem.table(classes = setOf("apps")) {
			for (app in apps) {
				showApp(app)
			}
		}
	}

	private fun Table.showApp(app: AppTokenData) {

		val elem = Tr(classes = setOf("app"))
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
				elem.parent?.remove(elem)
				Toast.success("Revoked access for ${app.appName}")
			}
		}

		// layout the UI
		add(elem)
		elem.td(app.appName, classes = setOf("name"))
		elem.td {
			div("Granted permissions:")
			showPermissions(app.permissions)
		}
		elem.td(classes = setOf("buttons")) {
			add(revokeButton)
		}
	}

	private fun Container.showPermissions(permissions: List<AppPermissionData>) {
		table(classes = setOf("permissions")) {
			for (permission in permissions) {
				tr(classes = setOf("permission")) {
					td(permission.appPermissionId, classes = setOf("title"))
					td(permission.description, classes = setOf("description"))
				}
			}
		}
	}

	private fun Tr.showToken(request: AppTokenRequestData, token: String) {

		removeAll()

		td {
			div(request.appName, classes = setOf("name"))
			div("Access approved!")
		}
		td {
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
