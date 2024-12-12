package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.lazyTabPanel
import edu.duke.bartesaghi.micromon.services.AdminInfo
import edu.duke.bartesaghi.micromon.services.AuthType
import edu.duke.bartesaghi.micromon.services.ClusterMode
import edu.duke.bartesaghi.micromon.views.admin.*
import kotlinext.js.jsObject
import io.kvision.jquery.JQueryAjaxSettings
import io.kvision.jquery.jQuery
import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.form.text.*
import io.kvision.html.*
import io.kvision.modal.Alert
import io.kvision.navbar.navLink


fun Widget.onGoToAdmin() {
	onGo(AdminView.path())
}

class AdminView : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.register("^${path()}$") {
				viewport.setView(AdminView())
			}
		}

		fun path() = "/admin"
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "admin"))

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Administration", icon = "fas fa-cogs")
					.onGoToAdmin()
			}
		}

		update(viewport)
	}

	private fun update(viewport: Viewport) {

		elem.removeAll()

		AppScope.launch {

			// get the current admin info
			val loadingElem = elem.loading("Fetching info ...")
			val info = try {
				Admin.info.get()
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loadingElem)
			}

			// what should we show?
			when {
				info.needsBootstrap -> elem.bootstrap(viewport, info.authType)
				info.adminLoggedIn -> elem.adminTabs(info)
				else -> elem.errorMessage("You're not an administrator")
			}
		}
	}

	private fun Container.bootstrap(viewport: Viewport, authType: AuthType) {

		// first-time use of the system
		// so we need to create the initial administrator

		h1("First-Time Setup")

		p("Looks like this is your first time using nextPYP. Let's set up your administrator user account.")

		var useridText: Text? = null
		var pw1Text: Text? = null
		var pw2Text: Text? = null
		if (authType == AuthType.Login) {
			useridText = text {
				label = "User ID"
			}
			pw1Text = password {
				label = "Password"
			}
			pw2Text = password {
				label = "Password again"
			}
		}

		val usernameText: Text = text {
			label = "Display Name"
		}

		button("Create Administrator").onClick click@{

			// Upload the password as a "file" so the JVM backend can
			// read the password into a byte/char buffer instead of a String object.
			// Putting passwords into String objects has known security flaws due to the
			// immutability and caching of JVM strings.
			jQuery.ajax(jsObject<JQueryAjaxSettings> {
				url = "/auth/bootstrap"
				method = "POST"
				contentType = "multipart/form-data; boundary=AaB03x"
				processData = false
				// make a multipart encoding for forms with uploaded files, see HTTP spec:
				// https://www.w3.org/TR/html401/interact/forms.html#h-17.13.4
				data = when (authType) {

					AuthType.ReverseProxy -> """
						|--AaB03x
						|Content-Disposition: form-data; name="username"
						|
						|${usernameText.value}
						|--AaB03x--
					""".trimMargin()

					AuthType.Login -> {

						val pw1 = pw1Text!!.value
						val pw2 = pw2Text!!.value

						// make sure the new passwords match
						if (pw1 != pw2) {
							// TODO: show inline error
							Alert.show(
								caption = "Form Error",
								text = "Passwords don't match"
							)
							return@click
						}

						"""
							|--AaB03x
							|Content-Disposition: form-data; name="userid"
							|
							|${useridText!!.value}
							|--AaB03x
							|Content-Disposition: form-data; name="username"
							|
							|${usernameText.value}
							|--AaB03x
							|Content-Disposition: form-data; name="password"; filename="password.txt"
							|Content-Type: text/plain
							|
							|$pw1
							|--AaB03x--
						""".trimMargin()
					}

					else -> throw Error()
				}
				// HTTP requires CR LF line endings
				.replace("\n", "\r\n")
			}).then(
				doneCallback = { _, _, _ ->
					// reset the form
					update(viewport)
				},
				failCallback = { _, _, error ->
					Alert.show(
						caption = "User creation Failed",
						text = "$error"
					)
				}
			)
		}
	}

	private fun Container.adminTabs(info: AdminInfo) {
		lazyTabPanel {

			if (info.authType.hasUsers || info.debug) {
				addTab("Users", "fas fa-users") { lazyTab ->
					AdminUsersTab(lazyTab.elem, info)
				}
			}

			addTab("Groups", "fas fa-layer-group") { lazyTab ->
				AdminGroupsTab(lazyTab.elem)
			}

			addTab("PYP", "fas fa-microscope") { lazyTab ->
				AdminPypTab(lazyTab.elem)
			}

			addTab("Jobs", "fas fa-user-cog") { lazyTab ->
				AdminJobsTab(lazyTab.elem, info)
			}

			when (info.clusterMode) {

				ClusterMode.Standalone -> {
					addTab("Standalone Jobs", "fas fa-microchip") { lazyTab ->
						AdminStandaloneTab(lazyTab.elem)
					}
				}

				// TODO: make a tool to query SLURM state?

				else -> Unit
			}

			addTab("Performance", "fas fa-tachometer-alt") { lazyTab ->
				AdminPerfTab(lazyTab.elem)
			}
		}
	}
}
