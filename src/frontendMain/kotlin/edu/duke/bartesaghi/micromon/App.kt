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

	companion object {

		/**
		 * A list of all the views for the JavaScript router.
		 * NOTE: Every view should have its entry here, or refreshing a page showing that view will fail with weird errors
		 */
		val VIEWS: List<Routed> =
			// NOTE: keeping this list in alphabetical order makes comaparing to the source files easier
			listOf(
				AdminView.Companion,
				AppsView.Companion,
				DashboardView.Companion,
				IntegratedRefinementView.Companion,
				LoginView.Companion,
				ProjectView.Companion,
				SessionsView.Companion,
				SetPasswordView.Companion,
				SingleParticleImportDataView.Companion,
				SingleParticlePreprocessingView.Companion,
				SingleParticleSessionDataView.Companion,
				SingleParticleSessionView.Companion,
				TomographyDenoisingEvalView.Companion,
				TomographyDenoisingTrainingView.Companion,
				TomographyImportDataView.Companion,
				TomographyImportDataPureView.Companion,
				TomographyMiloEvalView.Companion,
				TomographyMiloTrainView.Companion,
				TomographyParticlesEvalView.Companion,
				TomographyParticlesTrainView.Companion,
				TomographyPickingClosedView.Companion,
				TomographyPickingOpenView.Companion,
				TomographyPickingView.Companion,
				TomographyPreprocessingView.Companion,
				TomographyPurePreprocessingView.Companion,
				TomographySegmentationClosedView.Companion,
				TomographySegmentationOpenView.Companion,
				TomographySessionDataView.Companion,
				TomographySessionView.Companion,
				YourAccountView.Companion
			)

		/**
		 * Make sure the view is registered, by throwing an error if it's not.
		 */
		fun checkView(view: View) {

			if (view.routed == null) {
				// not a routed view, no need to register with the App
				return
			}

			if (VIEWS.none { it === view.routed }) {
				throw Error("View not registered with app: ${view::class.simpleName}")
			}
		}
	}

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
		routing.register(VIEWS, viewport)
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
