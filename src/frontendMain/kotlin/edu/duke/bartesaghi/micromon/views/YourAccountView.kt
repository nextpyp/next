package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.flatLazyTabPanel
import edu.duke.bartesaghi.micromon.components.lazyTabPanel
import edu.duke.bartesaghi.micromon.services.AdminInfo
import edu.duke.bartesaghi.micromon.services.ClusterMode
import edu.duke.bartesaghi.micromon.views.admin.*
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

		val bar = elem.div(classes = setOf("block-view-bar"))
		val content = elem.div(classes = setOf("block-view-content"))

		val session = Session.get()
		if (session == null) {
			content.h1("Not logged in")
			return
		}

		bar.div("Hello, ${session.name}", classes = setOf("job-meta-info"))

		AppScope.launch {
			val info = Admin.info.get()
			if (info.groups.isNotEmpty()) {
				bar.div("Group(s): ${info.groups.sortedBy { it.name }.joinToString(", ") { it.name }}", classes = setOf("job-meta-info"))
			}
		}

		content.lazyTabPanel {
			addTab("Apps", "fas fa-puzzle-piece") { lazyTab ->
				lazyTab.elem.add(AppsPanel(session))
			}
			addTab("Administration", "fas fa-cogs") { lazyTab ->
				lazyTab.elem.adminTab()
			}
		}
	}

	private fun Container.adminTab() {

		AppScope.launch {

			val loadingElem = loading("Fetching info ...")
			val info = try {
				Admin.info.get()
			} catch (t: Throwable) {
				errorMessage(t)
				return@launch
			} finally {
				remove(loadingElem)
			}

			when {
				info.needsBootstrap -> errorMessage("Administrator setup is required. Please visit the Administration page to complete first-time setup.")
				info.adminLoggedIn -> adminTabsContent(info)
				else -> errorMessage("You're not an administrator")
			}
		}
	}

	private fun Container.adminTabsContent(info: AdminInfo) {
		flatLazyTabPanel {

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

				else -> Unit
			}

			addTab("Performance", "fas fa-tachometer-alt") { lazyTab ->
				AdminPerfTab(lazyTab.elem)
			}
		}
	}
}
