package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.views.*
import io.kvision.Application
import io.kvision.core.Component
import io.kvision.panel.ContainerType
import io.kvision.panel.Root
import io.kvision.require
import io.kvision.startApplication
import io.kvision.toast.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch


class App : Application() {

	override fun start(state: Map<String,Any>) {

		// get the root element from the HTML
		// I AM ROOT!
        val root = Root("mmapp", ContainerType.FIXED)

		// load our CSS here, after CSS from KVision loads, so we can override
		require("css/micromon.css")

		// also load assets from npm libraries
		require("nouislider/distribute/nouislider.css")
		require("photoswipe/dist/photoswipe.css")
		require("photoswipe/dist/default-skin/default-skin.css")

		// build the viewport
		val viewport = Viewport(root)
		root.add(viewport)

		// set the default route to redirect to a default view
		routing.register {
			DashboardView.go()
		}

		// register views with the js router
		// ie, a list of registered companions =P
		val views = listOf(
			AdminView.Companion,
			LoginView.Companion,
			YourAccountView.Companion,
			SetPasswordView.Companion,
			DashboardView.Companion,
			ProjectView.Companion,
			SingleParticlePreprocessingView.Companion,
			TomographyPreprocessingView.Companion,
			TomographyPurePreprocessingView.Companion,
			TomographyDenoisingView.Companion,
			TomographyPickingView.Companion,
			TomographyPickingOpenView.Companion,
			TomographyPickingClosedView.Companion,
			TomographyParticlesMiloView.Companion,
			TomographyParticlesEvalView.Companion,
			IntegratedRefinementView.Companion,
			SessionsView.Companion,
			SingleParticleSessionView.Companion,
			TomographySessionView.Companion,
			AppsView.Companion
		)
		routing.register(views, viewport)
    }
}

val app = App()

class CoroutineScopeWrapper {

	private fun makeScope() =
		CoroutineScope(window.asCoroutineDispatcher())

	private var scope = makeScope()

	private val exceptionHandler = CoroutineExceptionHandler { _, t ->

		t.reportError()

		// show the user something
		Toast.error("The operation caused an unhandled error: ${t.message ?: "(unknown)"}")

		// recycle the coroutine scope
		// apparently having an unhandled exception puts the scope into some kind of
		// totally useless state where we can't launch any more tasks again ever
		// all we can do is just start over
		scope = makeScope()
	}

	fun launch(block: suspend CoroutineScope.() -> Unit) =
		scope.launch(exceptionHandler, block = block)

}

val AppScope = CoroutineScopeWrapper()

fun main() {
    startApplication(::App)
}

/**
 * Efficiently make multiple updates to the DOM at once.
 * KVision's use of Snabbdom is pretty inefficient, so we need to give it some hints to work fast.
 */
fun Component.batch(block: () -> Unit) =
	singleRender(block)
