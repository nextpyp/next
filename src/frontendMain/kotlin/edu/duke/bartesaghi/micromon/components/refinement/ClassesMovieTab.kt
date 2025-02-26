package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.components.ClassesMovie
import edu.duke.bartesaghi.micromon.services.JobData
import edu.duke.bartesaghi.micromon.views.IntegratedRefinementView
import edu.duke.bartesaghi.micromon.views.PathableTab
import edu.duke.bartesaghi.micromon.views.RegisterableTab
import edu.duke.bartesaghi.micromon.views.TabRegistrar
import io.kvision.html.Div


class ClassesMovieTab(
	val job: JobData,
	val state: IntegratedRefinementView.State,
) : Div(classes = setOf("classes-movie-tab")), PathableTab {

	companion object : RegisterableTab {

		const val pathFragment = "classesMovie"

		override fun registerRoutes(register: TabRegistrar) {
			register(pathFragment) {
				null
			}
		}
	}

	override var onPathChange = {}
	override var isActiveTab = false

	private val iterationsNav = state.iterationNav.clone()

	private val movie = ClassesMovie<Int>(
		job,
		imagePather = { iteration, classNum, size ->
			"/kv/reconstructions/${job.jobId}/$classNum/$iteration/images/map/${size.id}"
		}
	)

	override fun path(): String =
		pathFragment

	init {

		// layout the tab
		add(iterationsNav)
		add(movie)

		// wire up events
		iterationsNav.onShow = {
			val iteration = state.currentIteration
			val numClasses = state.currentIteration
				?.let { state.reconstructions.withIteration(it) }
				?.classes
				?.size
			movie.update(iteration, numClasses)
		}
	}
}
