package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.Storage
import edu.duke.bartesaghi.micromon.services.ImageSize
import edu.duke.bartesaghi.micromon.services.JobData
import io.kvision.form.check.CheckBox
import io.kvision.form.check.CheckBoxStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.span
import js.unshadow
import kotlinx.browser.document
import org.w3c.dom.Image


class ClassesMovie<IT>(
	val job: JobData,
	val imagePather: (IT, Int, ImageSize) -> String
) : Div(classes = setOf("classes-movie")) {

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

	private val classItems = ArrayList<ClassItem>()
	private val sortable = SortableList(classItems, classes = setOf("classes"))
	private val movie = Movie()
	private val contentElem = Div()

	private var currentIteration: IT? = null
	private var currentNumClasses: Int? = null

	init {

		// layout the tab
		add(contentElem)

		// wire up events
		sortable.onReorder = {
			movie.update()
		}
		movie.onResize = { newSize ->
			Storage.classesMovieSize = newSize
			movie.populate(currentIteration)
			movie.update()
		}
	}

	fun update(iteration: IT?, numClasses: Int?) {

		currentIteration = iteration
		currentNumClasses = numClasses

		contentElem.removeAll()

		@Suppress("NAME_SHADOWING")
		val numClasses = numClasses
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
		val self = this // Kotlin DSLs are dumb ...
		contentElem.div(classes = setOf("content")) {
			add(self.sortable)
			add(self.movie)
		}

		sortable.update()
		movie.populate(iteration)
		movie.update()
	}

	private inner class Movie : SizedPanel("Classes", Storage.classesMovieSize) {

		// because Kotlin DSLs have dumb restrictions
		private val self = this@ClassesMovie

		private val contentElem = Div()

		// NOTE: use raw Image HTML elements here (instead of KVision's shadow DOM monstrosities)
		// so we can efficiently cache the images in memory
		private val imageElems = HashMap<Int, Image>()
		private var imageElemsInOrder = ArrayList<Image>()

		private var playBar: PlayBar =
			PlayBar(1 .. imageElemsInOrder.size)
				.apply {
					looping = true
					boomeranging = true
					onFrame = this@Movie::updateFrame
				}

		init {
			// layout the control
			add(contentElem)
		}

		fun populate(iteration: IT?) {

			imageElems.clear()

			iteration ?: return

			for (classItem in self.classItems) {
				val img = Image()
				img.alt = "Class ${classItem.classNum}"
				img.src = self.imagePather(iteration, classItem.classNum, size)
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
			self.classItems
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
