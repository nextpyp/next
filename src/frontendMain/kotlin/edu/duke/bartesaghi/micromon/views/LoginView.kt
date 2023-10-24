package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import kotlinext.js.jsObject
import io.kvision.jquery.JQueryAjaxSettings
import io.kvision.jquery.jQuery
import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.form.text.password
import io.kvision.form.text.text
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.h1
import io.kvision.modal.Alert
import io.kvision.navbar.navLink


fun Widget.onGoToLogin() {
	onGo(LoginView.path())
}

class LoginView : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.register("^${path()}$") {
				viewport.setView(LoginView())
			}
		}

		fun path() = "/login"
	}

	override val elem = Div(classes = setOf("dock-page", "login"))
	override val showsUserChrome = false

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Your Account", icon = "fas fa-user")
					.onGoToYourAccount()
				navLink("Login", icon = "fas fa-sign-in-alt")
					.onGoToLogin()
			}
		}

		update(viewport)
	}

	private fun update(viewport: Viewport) {

		// what's our login status?
		val session = Session.get()

		elem.removeAll()
		if (session == null) {
			elem.loginForm()
		} else {
			elem.logoutForm(viewport, session)
		}
	}

	private fun Container.loginForm() {

		h1("Login")

		val usernameText = text {
			label = "Username"
		}
		val passwordText = password {
			label = "Password"
		}
		val loginButton = button("Login")

		fun login() {

			// Upload the password as a "file" so the JVM backend can
			// read the password into a byte/char buffer instead of a String object.
			// Putting passwords into String objects has known security flaws due to the
			// immutability and caching of JVM strings.
			jQuery.ajax(jsObject<JQueryAjaxSettings> {
				url = "/auth/login"
				method = "POST"
				contentType = "multipart/form-data; boundary=AaB03x"
				processData = false
				// make a multipart encoding for forms with uploaded files, see HTTP spec:
				// https://www.w3.org/TR/html401/interact/forms.html#h-17.13.4
				data = """
						|--AaB03x
						|Content-Disposition: form-data; name="username"
						|
						|${usernameText.value}
						|--AaB03x
						|Content-Disposition: form-data; name="password"; filename="password.txt"
						|Content-Type: text/plain
						|
						|${passwordText.value}
						|--AaB03x--
					""".trimMargin()
					// HTTP requires CR LF line endings
					.replace("\n", "\r\n")
			}).then(
				doneCallback = { response, status, xhr ->
					DashboardView.go()
				},
				failCallback = { xhr, status, error ->
					Alert.show(
						caption = "Login Failed",
						text = "$error"
					)
				}
			)
		}

		// wire up events
		loginButton.onClick {
			login()
		}
		passwordText.onEvent {
			keypress = { event ->
				if (event.keyCode == 13) { // enter/return
					login()
				}
			}
		}
	}

	private fun Container.logoutForm(viewport: Viewport, session: User.Session) {

		h1("Hi, ${session.name}")

		button("Logout").onClick {

			jQuery.ajax(jsObject<JQueryAjaxSettings> {
				url = "/auth/logout"
				method = "GET"
			}).then(
				doneCallback = { response, status, xhr ->
					// reset the form
					update(viewport)
				},
				failCallback = { xhr, status, error ->
					Alert.show(
						caption = "Logout Failed",
						text = "$error"
					)
				}
			)
		}
	}
}
