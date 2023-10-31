package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.Storage
import edu.duke.bartesaghi.micromon.components.PlayBar
import edu.duke.bartesaghi.micromon.components.SizedPanel
import edu.duke.bartesaghi.micromon.components.SortableList
import edu.duke.bartesaghi.micromon.services.ImageSize
import edu.duke.bartesaghi.micromon.services.JobData
import edu.duke.bartesaghi.micromon.services.ReconstructionData
import edu.duke.bartesaghi.micromon.views.IntegratedRefinementView
import edu.duke.bartesaghi.micromon.views.PathableTab
import edu.duke.bartesaghi.micromon.views.RegisterableTab
import edu.duke.bartesaghi.micromon.views.TabRegistrar
import io.kvision.form.check.CheckBox
import io.kvision.form.check.CheckBoxStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.span
import js.unshadow
import kotlinx.browser.document
import org.w3c.dom.Image


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

	data class ClassItem(val classNum: Int) : Div(classes = setOf("class-item")), SortableList.Item {

		override val key = classNum.toString()

		val check = CheckBox(true).apply {
			style = CheckBoxStyle.PRIMARY
		}

		init {
			span("Class $classNum", classes = setOf("label"))
			add(check)
		}
	}

	override var onPathChange = {}
	override var isActiveTab = false
	private val iterationNav = state.iterationNav.clone()

	private val classItems = ArrayList<ClassItem>()
	private val sortable = SortableList(classItems, classes = setOf("classes"))
	private val movie = Movie()
	private val contentElem = Div()

	override fun path(): String =
		pathFragment

	init {

		// layout the tab
		div {
			add(this@ClassesMovieTab.iterationNav)
			add(this@ClassesMovieTab.contentElem)
		}

		// wire up events
		iterationNav.onShow = {
			update()
		}
		sortable.onReorder = {
			movie.update()
		}

		update()
	}

	private fun update() {

		contentElem.removeAll()

		// grab the classes for this iteration
		val numClasses = state.currentIteration
			?.let { state.reconstructions.withIteration(it) }
			?.classes
			?.size
			?: 0

		if (numClasses <= 0) {
			div("(No classes to show)", classes = setOf("empty", "spaced"))
			return
		}

		// add items for classes if we need more, or remove them if we need fewer
		// but keep the order of the exising items, which may have been sorted by the user
		while (classItems.size > numClasses) {
			classItems.removeLast()
		}
		while (classItems.size < numClasses) {
			val classNum = classItems.size + 1
			val item = ClassItem(classNum)
			item.check.onClick {
				movie.update()
			}
			classItems.add(item)
		}

		// layout the content
		contentElem.div(classes = setOf("content")) {
			add(this@ClassesMovieTab.sortable)
			add(this@ClassesMovieTab.movie)
		}

		sortable.update()
		movie.populate()
		movie.update()
	}


	private fun imageUrl(reconstruction: ReconstructionData, size: ImageSize) =
		"/kv/reconstructions/${job.jobId}/${reconstruction.classNum}/${reconstruction.iteration}/images/map/${size.id}"


	private inner class Movie : SizedPanel("Classes", Storage.classesMovieSize) {

		// because Kotlin DSLs have dumb restrictions
		private val tab = this@ClassesMovieTab

		private val contentElem = Div()

		// NOTE: use raw Image HTML elements here (instead of KVision's shadow DOM monstrosities)
		// so we can efficiently cache the images in memory
		private val imageElems = HashMap<Int,Image>()
		private var imageElemsInOrder = ArrayList<Image>()

		private var playBar: PlayBar =
			PlayBar(1 .. imageElemsInOrder.size)
				.apply {
					looping = true
					boomeranging = true
					onFrame = this@Movie::updateFrame
				}

		init {

			add(contentElem)

			// wire up events
			onResize = { newSize ->
				Storage.classesMovieSize = newSize
				populate()
				update()
			}
		}

		fun populate() {

			imageElems.clear()

			val iteration = tab.state.currentIteration
				?: return

			for (classItem in tab.classItems) {
				val img = Image()
				img.alt = "Class ${classItem.classNum}"
				tab.state.reconstructions
					.withIteration(iteration)
					?.withClass(classItem.classNum)
					?.let { img.src = tab.imageUrl(it, size) }
					?: "img/placeholder/${size.id}"
				imageElems[classItem.classNum] = img

				// add the images to the real DOM so they can preload
				// NOTE: Set the opacity to 0 here rather than setting the size to zero or the display to none,
				// so the browser won't try to optimize away the image prep.
				// If the browser skips prepping the image properly, we get flickers on the first pass of the animation.
				img.style.opacity = "0"
				document.body?.append(img)
			}
		}

		fun update() {

			contentElem.removeAll()

			// filter the classes to get the images
			imageElemsInOrder.clear()
			tab.classItems
				.filter { it.check.value }
				.mapNotNull { imageElems[it.classNum] }
				.forEach { imageElemsInOrder.add(it) }

			if (imageElemsInOrder.isEmpty()) {
				return
			}

			// layout the UI
			contentElem.div {
				for (img in this@Movie.imageElemsInOrder) {
					img.style.opacity = "initial"
					img.style.display = "none"
					add(unshadow(img))
				}
			}
			contentElem.add(playBar)

			playBar.frames = 1 .. imageElemsInOrder.size
			updateFrame(1)
		}

		private fun updateFrame(frame: Int) {

			for (i in 0 until imageElemsInOrder.size) {
				val img = imageElemsInOrder[i]
				img.style.display =
					if (frame == i + 1) {
						"block"
					} else {
						"none"
					}
			}

			panelTitle = imageElemsInOrder
				.getOrNull(frame - 1)
				?.alt
				?: "(unknown class)"
		}
	}
}
