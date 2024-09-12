package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.AuthType
import edu.duke.bartesaghi.micromon.services.Services
import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink


fun Widget.onGoToYourAccount() {
	onGo(YourAccountView.path())
}

class YourAccountView : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.register("^${path()}$") {
				viewport.setView(YourAccountView())
			}
		}

		fun path() = "/account"
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "your-account"))

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Your Account", icon = "fas fa-user")
					.onGoToYourAccount()
			}
		}

		// what's our login status?
		val session = Session.get()

		// show the appropriate form
		if (session != null) {
			elem.yourAccount(session)
		} else {
			elem.h1("Not logged in")
		}
	}

	private fun Container.yourAccount(session: User.Session) {

		AppScope.launch {

			// check with the backend to see what kind of login system we're using
			val loadingElem = loading("Checking...")
			val info = try {
				Services.admin.getInfo()
			} catch (t: Throwable) {
				errorMessage(t)
				null
			} finally {
				remove(loadingElem)
			}

			h1("Hello, ${session.name}")

			val links = listTag(ListType.UL) {

				if (info?.authType == AuthType.Login) {
					link("Login/Logout").onGoToLogin()
					if (!info.demoLoggedIn) {
						link("Change Password").onGoToSetPassword()
					}
				}

				link("Apps").onGoToApps()

				// show a link to the admin page, if the user is an admin
				if (info?.adminLoggedIn == true) {
					link("Administration").onGoToAdmin()
				}

				link("Return to Dashboard").onGoToDashboard()
			}

			if (links.getChildren().isEmpty()) {
				div("(No options for you here)")
			}
		}
	}
}