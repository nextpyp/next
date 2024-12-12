package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.AuthType
import edu.duke.bartesaghi.micromon.views.admin.Admin
import org.w3c.dom.get
import io.kvision.html.*
import io.kvision.panel.Root
import kotlinx.browser.document
import kotlinx.dom.addClass
import kotlinx.dom.removeClass


class Viewport(val rootElem: Root) : Div(classes = setOf("viewport")) {

	val htmlElem = document.getElementsByTagName("html")[0]
	val bodyElem = document.getElementsByTagName("body")[0]

	val userElem = Div(classes = setOf("user-chrome"))
	val navbarElem = NavbarEx().apply {
		// add the blab logo to the nav bar
		// clicking it sends you to the homepage
		brand.link("", "#/") {
			image("nextPYP_logo.svg", classes = setOf("logo"), alt = "nextPYP")
		}
		brand.span("v${BuildData.version}", classes = setOf("version"))
	}

	val headerElem = div(classes = setOf("viewport-header")) {
		val viewport = this@Viewport
		add(viewport.navbarElem)
		add(viewport.userElem)
	}
	val viewsElem = div(classes = setOf("views"))

	private var currentView: View? = null

	fun updateUserChrome() {

		userElem.removeAll()

		// show a help link
		userElem.link("Help", "https://nextpyp.app/files/pyp/${BuildData.version}/docs/index.html", target = "_blank", icon = "fas fa-question-circle", classes = setOf("dashboard-status-icon"))

		// show a user link, or a login button, if needed
		val session = Session.get()
		if (session != null) {
			userElem.link(session.name, icon = "fas fa-user").onGoToYourAccount()
		} else {
			AppScope.launch {
				val info = Admin.info.get()
				when (info.authType) {

					AuthType.Login -> {
						userElem.link("Login", icon = "fas fa-sign-in-alt")
							.onGoToLogin()
					}

					else -> Unit
				}
			}
		}
	}

	fun setView(view: View) {

		// safety: check that this view is registered in the app
		App.checkView(view)

		// update the viewport mode
		for (mode in View.Mode.values()) {
			htmlElem?.removeClass(mode.className)
			bodyElem?.removeClass(mode.className)
			rootElem.removeCssClass(mode.className)
			removeCssClass(mode.className)
		}
		view.mode?.let { mode ->
			htmlElem?.addClass(mode.className)
			bodyElem?.addClass(mode.className)
			rootElem.addCssClass(mode.className)
			addCssClass(mode.className)
		}

		// update the user chrome
		if (view.showsUserChrome) {
			updateUserChrome()
			userElem.visible = true
		} else {
			userElem.visible = false
		}

		// cleanup the old view
		currentView?.let { oldView ->

			// remove the old view
			oldView.close()
			viewsElem.remove(oldView.elem)
		}
		currentView = null

		// always clear the navbar
		navbarElem.removeAll()

		// add the new view
		viewsElem.add(view.elem)
		currentView = view

		// let the view initialize itself
		view.init(this)
	}
}
