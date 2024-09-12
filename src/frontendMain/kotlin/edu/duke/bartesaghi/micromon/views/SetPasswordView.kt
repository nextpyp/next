package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import kotlinext.js.jsObject
import io.kvision.jquery.JQueryAjaxSettings
import io.kvision.jquery.jQuery
import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.form.text.password
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.h1
import io.kvision.modal.Alert
import io.kvision.navbar.navLink


fun Widget.onGoToSetPassword() {
	onGo(SetPasswordView.path())
}

class SetPasswordView : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.register("^${path()}$") {
				viewport.setView(SetPasswordView())
			}
		}

		fun path() = "/pw"
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "pw"))

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Your Account", icon = "fas fa-user")
					.onGoToYourAccount()
				navLink("Set Password", icon = "fas fa-key")
					.onGoToSetPassword()
			}
		}

		// what's our login status?
		val session = Session.get()

		// show the appropriate form
		elem.removeAll()
		if (session != null) {
			elem.pwForm(session)
		} else {
			elem.h1("Not logged in")
		}
	}

	private fun Container.pwForm(session: User.Session) {

		h1("Choose a new password")

		val oldpwText = if (session.haspw) {
			password {
				label = "Old Password"
			}
		} else {
			null
		}

		val newpw1Text = password {
			label = "New Password"
		}
		val newpw2Text = password {
			label = "New Password Again"
		}

		button("Set Password").onClick click@{

			// make sure the new passwords match
			if (newpw1Text.value != newpw2Text.value) {
				Alert.show(
					caption = "Form Error",
					text = "New passwords don't match"
				)
				return@click
			}

			// Upload the passwords as "files" so the JVM backend can
			// read the password into a byte/char buffer instead of a String object.
			// Putting passwords into String objects has known security flaws due to the
			// immutability and caching of JVM strings.
			jQuery.ajax(jsObject<JQueryAjaxSettings> {
				url = "/auth/setpw"
				method = "POST"
				contentType = "multipart/form-data; boundary=AaB03x"
				processData = false
				// make a multipart encoding for forms with uploaded files, see HTTP spec:
				// https://www.w3.org/TR/html401/interact/forms.html#h-17.13.4
				data = """
					|--AaB03x
					|Content-Disposition: form-data; name="oldPassword"; filename="oldPassword.txt"
					|Content-Type: text/plain
					|
					|${oldpwText?.value ?: ""}
					|--AaB03x
					|Content-Disposition: form-data; name="newPassword"; filename="newPassword.txt"
					|Content-Type: text/plain
					|
					|${newpw1Text.value ?: ""}
					|--AaB03x--
				""".trimMargin()
					// HTTP requires CR LF line endings
					.replace("\n", "\r\n")
			}).then(
				doneCallback = { response, status, xhr ->

					// reset the form
					oldpwText?.value = null
					newpw1Text.value = null
					newpw2Text.value = null

					Alert.show(
						caption = "Success",
						text = "Password changed successfully!"
					)
				},
				failCallback = { xhr, status, error ->
					Alert.show(
						caption = "Password change failed",
						text = xhr.responseText
					)
				}
			)
		}
	}
}
